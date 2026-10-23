param([string]$Destination)

$ErrorActionPreference = 'Stop'
$project = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\neoforge-1.21.1'))
if (-not $Destination) { $Destination = Join-Path $project 'minecraft-sources' }
$Destination = [System.IO.Path]::GetFullPath($Destination)

Add-Type -AssemblyName System.IO.Compression.FileSystem
$sourceJar = $null
foreach ($candidate in Get-ChildItem (Join-Path $project 'build') -Recurse -File -Filter '*sources*.jar') {
    $archive = [System.IO.Compression.ZipFile]::OpenRead($candidate.FullName)
    try {
        if ($archive.GetEntry('net/minecraft/world/item/ItemStack.java')) {
            $sourceJar = $candidate
            break
        }
    } finally { $archive.Dispose() }
}
if (-not $sourceJar) { throw 'Minecraft source archive not found. Run gradlew.bat createMinecraftArtifacts first.' }
if (Test-Path -LiteralPath $Destination) {
    throw "Destination already exists; existing source files were preserved. Choose a new -Destination to export again: $Destination"
}

[System.IO.Compression.ZipFile]::ExtractToDirectory($sourceJar.FullName, $Destination)
$javaCount = @(Get-ChildItem $Destination -Recurse -File -Filter '*.java').Count
$metadata = [ordered]@{
    minecraft = '1.21.1'
    neoforge = '21.1.259'
    source_archive = $sourceJar.FullName
    source_sha256 = (Get-FileHash $sourceJar.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    java_files = $javaCount
    generated_by = 'NeoForge ModDevGradle / NeoForm Runtime'
    purpose = 'Local source reading. Edit your mod in src/main/java, not this generated reference tree.'
}
$metadata | ConvertTo-Json | Set-Content (Join-Path $Destination '.source-archive.json') -Encoding utf8
Write-Output "Exported $javaCount Java files to $Destination"
Write-Output "Source archive: $($sourceJar.FullName)"
