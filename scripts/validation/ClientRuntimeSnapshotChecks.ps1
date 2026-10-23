param([string]$WorkspaceRoot = [IO.Directory]::GetParent([IO.Directory]::GetParent($PSScriptRoot).FullName).FullName)

$ErrorActionPreference = 'Stop'
$taskUtf8 = New-Object System.Text.UTF8Encoding($false)
$taskFixture = Join-Path $WorkspaceRoot ('logs/client-runtime-snapshot-checks/' + [Guid]::NewGuid().ToString('N'))
$taskProject = Join-Path $taskFixture 'project'
$taskBuild = Join-Path $taskProject 'build'
$taskHelper = Join-Path $WorkspaceRoot 'scripts/create-client-snapshot.ps1'
$taskSourceClasses = Join-Path $taskBuild 'classes/java/main/com/example/examplemod'
$taskClassState = Join-Path $taskSourceClasses 'zones/match/MatchSpectatorService$State.class'
$taskCheckCount = 0

function Assert-Task([bool]$Condition, [string]$Reason) {
    if (-not $Condition) { throw $Reason }
    $script:taskCheckCount++
}
function Write-TaskText([string]$Path, [string]$Text) {
    New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($Path)) -Force | Out-Null
    [IO.File]::WriteAllText($Path, $Text, $taskUtf8)
}

Write-TaskText (Join-Path $taskProject 'src/main/java/com/example/examplemod/ExampleMod.java') 'package com.example.examplemod; public class ExampleMod {}'
Write-TaskText (Join-Path $taskProject 'src/main/java/com/example/examplemod/zones/match/MatchSpectatorService.java') 'package com.example.examplemod.zones.match; public class MatchSpectatorService { record State(int id) {} }'
foreach ($taskClass in @((Join-Path $taskSourceClasses 'ExampleMod.class'), (Join-Path $taskSourceClasses 'zones/match/MatchSpectatorService.class'), $taskClassState)) {
    New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($taskClass)) -Force | Out-Null
    [IO.File]::WriteAllBytes($taskClass, [byte[]]@(202, 254, 186, 190, 0, 0, 0, 65))
}
Write-TaskText (Join-Path $taskBuild 'resources/main/META-INF/neoforge.mods.toml') 'modLoader="javafml"'
Write-TaskText (Join-Path $taskBuild 'moddev/artifacts/fixture.jar') 'immutable build artifact fixture'
$taskVmArgs = '-DlegacyClassPath.file=' + (Join-Path $taskBuild 'moddev/clientLegacyClasspath.txt').Replace('\', '\\')
Write-TaskText (Join-Path $taskBuild 'moddev/clientRunVmArgs.txt') $taskVmArgs
Write-TaskText (Join-Path $taskBuild 'moddev/clientLegacyClasspath.txt') (Join-Path $taskBuild 'moddev/artifacts/fixture.jar')
Write-TaskText (Join-Path $taskBuild 'moddev/runClient.cmd') ('@echo off' + "`r`n" + 'java.exe @"' + (Join-Path $taskBuild 'moddev/clientRunVmArgs.txt') + '" -Dfml.modFolders=examplemod%%%%' + (Join-Path $taskBuild 'classes/java/main') + ';examplemod%%%%' + (Join-Path $taskBuild 'resources/main') + ' fixture.NeverExecuted')

$taskPointer = Join-Path $taskFixture 'request/one.json'
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $taskHelper -ProjectRoot $taskProject -PointerFile $taskPointer
Assert-Task ($LASTEXITCODE -eq 0) 'A complete prepared fixture should freeze successfully.'
$taskSnapshot = Get-Content -LiteralPath $taskPointer -Raw | ConvertFrom-Json
$taskManifest = Get-Content -LiteralPath $taskSnapshot.manifest -Raw | ConvertFrom-Json
Assert-Task $taskManifest.complete 'A usable snapshot must have a complete manifest.'
Assert-Task ($taskManifest.source_types_verified -eq 2) 'Every source outer type should be verified.'
Assert-Task ($taskManifest.file_count -eq 8) 'All classes, resources, launcher arguments and generated artifacts must be copied.'
Assert-Task (-not $taskSnapshot.snapshot.StartsWith($taskBuild + '\')) 'Snapshots must live outside mutable build output.'
$taskLaunchText = [IO.File]::ReadAllText($taskSnapshot.launcher)
Assert-Task (-not $taskLaunchText.Contains($taskBuild)) 'The frozen launcher must not refer to original classes/resources.'
Assert-Task ($taskLaunchText.Contains((Join-Path $taskSnapshot.snapshot 'build/classes/java/main'))) 'The official mod-folder argument must load frozen classes.'
$taskFrozenVmArgs = [IO.File]::ReadAllText((Join-Path $taskSnapshot.snapshot 'build/moddev/clientRunVmArgs.txt'))
Assert-Task ($taskFrozenVmArgs.Contains((Join-Path $taskSnapshot.snapshot 'build/moddev/clientLegacyClasspath.txt').Replace('\', '\\'))) 'Java argument-file escaped paths must be redirected too.'
foreach ($taskRecord in $taskManifest.files) {
    Assert-Task ((Get-FileHash -LiteralPath (Join-Path $taskSnapshot.snapshot ('build/' + $taskRecord.relative)) -Algorithm SHA256).Hash.ToLowerInvariant() -eq $taskRecord.frozen_sha256) 'Every frozen manifest file must match its final SHA256.'
}

# Simulate the original problem by changing a compiled live-build file after the snapshot.
# No game or JVM is launched, and no actual project output is modified.
[IO.File]::WriteAllBytes($taskClassState, [byte[]]@(1, 2, 3))
$taskFrozenState = Join-Path $taskSnapshot.snapshot 'build/classes/java/main/com/example/examplemod/zones/match/MatchSpectatorService$State.class'
Assert-Task ((Get-Item -LiteralPath $taskFrozenState).Length -eq 8) 'A later build overwrite must not change a frozen lazily loaded inner class.'

# A missing inner class must stop launch preparation, leaving no usable request pointer.
$taskMissingProject = Join-Path $taskFixture 'missing-state-project'
New-Item -ItemType Directory -Path $taskMissingProject -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $taskProject 'src') -Destination (Join-Path $taskMissingProject 'src') -Recurse
Copy-Item -LiteralPath (Join-Path $taskProject 'build') -Destination (Join-Path $taskMissingProject 'build') -Recurse
$taskMissingState = Join-Path $taskMissingProject 'build/classes/java/main/com/example/examplemod/zones/match/MatchSpectatorService$State.class'
# Keep all operations in this verified fixture tree and use one PowerShell filesystem API.
Assert-Task ([IO.Path]::GetFullPath($taskMissingState).StartsWith([IO.Path]::GetFullPath($taskFixture) + '\', [StringComparison]::OrdinalIgnoreCase)) 'Negative fixture deletion must stay inside its named test directory.'
Remove-Item -LiteralPath $taskMissingState
$taskMissingPointer = Join-Path $taskFixture 'request/missing.json'
$taskOriginalErrorPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $taskFailureOutput = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $taskHelper -ProjectRoot $taskMissingProject -PointerFile $taskMissingPointer 2>&1
    $taskFailureExit = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $taskOriginalErrorPreference
}
Assert-Task ($taskFailureExit -ne 0) 'A missing inner class must reject the snapshot.'
Assert-Task (-not (Test-Path -LiteralPath $taskMissingPointer)) 'A failed snapshot must never create a successful launch pointer.'
Assert-Task (($taskFailureOutput | Out-String).Contains('Critical compiled class missing')) 'The rejection must identify the actual missing compiled class.'
Write-Output ("ClientRuntimeSnapshotChecks: {0} checks passed; no game launched. Evidence: {1}" -f $taskCheckCount, $taskFixture)
