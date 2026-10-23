param(
    [Parameter(Mandatory = $true)][string]$ProjectRoot,
    [Parameter(Mandatory = $true)][string]$PointerFile
)

$ErrorActionPreference = 'Stop'
$taskUtf8 = New-Object System.Text.UTF8Encoding($false)
$taskProject = [IO.Path]::GetFullPath($ProjectRoot).TrimEnd('\', '/')
$taskWorkspace = [IO.Directory]::GetParent($taskProject).FullName
$taskBuild = Join-Path $taskProject 'build'
$taskSnapshotRoot = Join-Path $taskWorkspace '.runtime/client-snapshots'
$taskSnapshotId = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffffffZ') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$taskSnapshot = Join-Path $taskSnapshotRoot $taskSnapshotId
$taskSnapshotBuild = Join-Path $taskSnapshot 'build'
$taskPointer = [IO.Path]::GetFullPath($PointerFile)

function Get-TaskHash([string]$Path) {
    # Hash through .NET so preparing through an existing Gradle daemon does not depend on
    # its inherited PSModulePath or the Get-FileHash module auto-loading configuration.
    $taskStream = [IO.File]::OpenRead($Path)
    $taskHasher = [Security.Cryptography.SHA256]::Create()
    try {
        return [BitConverter]::ToString($taskHasher.ComputeHash($taskStream)).Replace('-', '').ToLowerInvariant()
    } finally {
        $taskHasher.Dispose()
        $taskStream.Dispose()
    }
}

if (-not $taskSnapshot.StartsWith($taskWorkspace + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Client snapshot must stay inside the workspace.'
}
if (Test-Path -LiteralPath $taskSnapshot) { throw 'Snapshot IDs must never overwrite an existing client.' }

$taskMappings = @('classes/java/main', 'resources/main', 'moddev')
foreach ($taskRelative in $taskMappings) {
    if (-not (Test-Path -LiteralPath (Join-Path $taskBuild $taskRelative) -PathType Container)) {
        throw "Build output missing: $taskRelative. The full classes/resources preparation must succeed first."
    }
}
$taskGeneratedLauncher = Join-Path $taskBuild 'moddev/runClient.cmd'
if (-not (Test-Path -LiteralPath $taskGeneratedLauncher -PathType Leaf)) {
    throw 'The official ModDevGradle client launcher was not generated.'
}

# Fail at preparation if an incomplete compile removed an outer class. This checks the whole
# source set rather than hiding the specific NoClassDefFoundError reported during bot death.
$taskSourceRoot = Join-Path $taskProject 'src/main/java'
$taskClasses = Join-Path $taskBuild 'classes/java/main'
$taskSourceTypes = 0
foreach ($taskJavaFile in Get-ChildItem -LiteralPath $taskSourceRoot -Recurse -File -Filter '*.java') {
    $taskTypeName = [Regex]::Escape($taskJavaFile.BaseName)
    $taskJavaText = [IO.File]::ReadAllText($taskJavaFile.FullName)
    if ($taskJavaText -notmatch ('\b(?:class|interface|enum|record)\s+' + $taskTypeName + '\b')) { continue }
    $taskRelativeType = $taskJavaFile.FullName.Substring($taskSourceRoot.Length + 1)
    $taskClassFile = Join-Path $taskClasses ([IO.Path]::ChangeExtension($taskRelativeType, '.class'))
    if (-not (Test-Path -LiteralPath $taskClassFile -PathType Leaf)) {
        throw "Incomplete compiled output: $taskRelativeType has no class. Do not start this client."
    }
    $taskSourceTypes++
}
foreach ($taskRequired in @('com/example/examplemod/ExampleMod.class', 'com/example/examplemod/zones/match/MatchSpectatorService$State.class')) {
    if (-not (Test-Path -LiteralPath (Join-Path $taskClasses $taskRequired) -PathType Leaf)) {
        throw "Critical compiled class missing: $taskRequired"
    }
}
if (-not (Test-Path -LiteralPath (Join-Path $taskBuild 'resources/main/META-INF/neoforge.mods.toml'))) {
    throw 'Generated mod metadata is missing from the prepared resources.'
}

$taskRecords = [Collections.Generic.List[object]]::new()
foreach ($taskRelative in $taskMappings) {
    $taskInput = Join-Path $taskBuild $taskRelative
    foreach ($taskFile in Get-ChildItem -LiteralPath $taskInput -Recurse -File) {
        $taskRecords.Add([pscustomobject]@{
            relative = $taskFile.FullName.Substring($taskBuild.Length + 1)
            source_sha256 = Get-TaskHash $taskFile.FullName
            bytes = $taskFile.Length
        })
    }
}
if ($taskRecords.Count -eq 0) { throw 'Cannot create an empty client snapshot.' }
New-Item -ItemType Directory -Path $taskSnapshotBuild -Force | Out-Null
foreach ($taskRelative in $taskMappings) {
    $taskInput = Join-Path $taskBuild $taskRelative
    $taskOutput = Join-Path $taskSnapshotBuild $taskRelative
    New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($taskOutput)) -Force | Out-Null
    Copy-Item -LiteralPath $taskInput -Destination $taskOutput -Recurse
}

foreach ($taskRecord in $taskRecords) {
    $taskCopied = Join-Path $taskSnapshotBuild $taskRecord.relative
    if ((Get-TaskHash $taskCopied) -ne $taskRecord.source_sha256) {
        throw "Snapshot copy was incomplete: $($taskRecord.relative)"
    }
}

# Keep official JVM/module/run arguments, replacing only mutable project-build paths.
# Cover both normal Windows paths and the doubled slashes in Java argument files.
$taskRewrittenFiles = [Collections.Generic.List[string]]::new()
foreach ($taskFile in Get-ChildItem -LiteralPath (Join-Path $taskSnapshotBuild 'moddev') -Recurse -File) {
    if ($taskFile.Extension -notin @('.cmd', '.txt', '.xml', '.properties', '.json')) { continue }
    $taskText = [IO.File]::ReadAllText($taskFile.FullName)
    $taskRewritten = $taskText.Replace($taskBuild.Replace('\', '\\'), $taskSnapshotBuild.Replace('\', '\\'))
    $taskRewritten = $taskRewritten.Replace($taskBuild.Replace('\', '/'), $taskSnapshotBuild.Replace('\', '/'))
    $taskRewritten = $taskRewritten.Replace($taskBuild, $taskSnapshotBuild)
    if ($taskRewritten -ne $taskText) {
        [IO.File]::WriteAllText($taskFile.FullName, $taskRewritten, $taskUtf8)
        $taskRewrittenFiles.Add($taskFile.FullName.Substring($taskSnapshot.Length + 1))
    }
    if ($taskRewritten.Contains($taskBuild) -or $taskRewritten.Contains($taskBuild.Replace('\', '\\')) -or $taskRewritten.Contains($taskBuild.Replace('\', '/'))) {
        throw "Mutable build reference remains in snapshot arguments: $($taskFile.Name)"
    }
}

# Verify inputs again while the preparing Gradle invocation still owns the build lock.
# An external editor/compiler writing outputs during the copy must cause a failed preparation.
$taskFinalCount = 0
foreach ($taskRelative in $taskMappings) {
    $taskFinalCount += @(Get-ChildItem -LiteralPath (Join-Path $taskBuild $taskRelative) -Recurse -File).Count
}
if ($taskFinalCount -ne $taskRecords.Count) { throw 'Build outputs changed while freezing the client.' }
foreach ($taskRecord in $taskRecords) {
    if ((Get-TaskHash (Join-Path $taskBuild $taskRecord.relative)) -ne $taskRecord.source_sha256) {
        throw "Build output changed while freezing: $($taskRecord.relative)"
    }
    $taskRecord | Add-Member -NotePropertyName frozen_sha256 -NotePropertyValue (Get-TaskHash (Join-Path $taskSnapshotBuild $taskRecord.relative))
}
$taskFrozenLauncher = Join-Path $taskSnapshotBuild 'moddev/runClient.cmd'
$taskLaunchText = [IO.File]::ReadAllText($taskFrozenLauncher)
foreach ($taskFolder in @('classes\java\main', 'resources\main')) {
    if (-not $taskLaunchText.Contains((Join-Path $taskSnapshotBuild $taskFolder))) {
        throw "The launcher is not loading frozen $taskFolder."
    }
}
$taskManifest = [ordered]@{
    schema = 'pubg-client-runtime-snapshot/1'
    prepared_utc = [DateTime]::UtcNow.ToString('o')
    snapshot = $taskSnapshot
    project = $taskProject
    launcher = $taskFrozenLauncher
    source_types_verified = $taskSourceTypes
    file_count = $taskRecords.Count
    rewritten_argument_files = $taskRewrittenFiles
    files = $taskRecords
    complete = $true
    game_started_by_preparation = $false
}
[IO.File]::WriteAllText((Join-Path $taskSnapshot 'manifest.json'), ($taskManifest | ConvertTo-Json -Depth 8), $taskUtf8)
New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($taskPointer)) -Force | Out-Null
$taskPointerValue = [ordered]@{ snapshot = $taskSnapshot; launcher = $taskFrozenLauncher; manifest = (Join-Path $taskSnapshot 'manifest.json'); complete = $true }
[IO.File]::WriteAllText($taskPointer, ($taskPointerValue | ConvertTo-Json), $taskUtf8)
Write-Output ("Prepared frozen client: {0} ({1} files, {2} source types); game not started." -f $taskSnapshotId, $taskRecords.Count, $taskSourceTypes)
