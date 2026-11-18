param([switch]$PrepareOnly)

$ErrorActionPreference = 'Stop'
$taskWorkspace = [IO.Directory]::GetParent($PSScriptRoot).FullName
$taskProject = Join-Path $taskWorkspace 'neoforge-1.21.1'
$taskRequest = Join-Path $taskProject ('.runtime/client-launch-requests/' + [Guid]::NewGuid().ToString('N') + '.json')
$taskExitCode = 0
Push-Location -LiteralPath $taskProject
try {
    # A unique pointer prevents a second launcher from replacing the snapshot selected by this run.
    & (Join-Path $taskProject 'gradlew.bat') '--console=plain' '--no-configuration-cache' 'prepareClientSnapshot' ("-PclientSnapshotPointer=" + $taskRequest)
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    if (-not (Test-Path -LiteralPath $taskRequest -PathType Leaf)) { throw 'Build finished without a complete runtime snapshot.' }
    $taskSnapshot = Get-Content -LiteralPath $taskRequest -Raw | ConvertFrom-Json
    $taskManifest = Get-Content -LiteralPath $taskSnapshot.manifest -Raw | ConvertFrom-Json
    if (-not $taskSnapshot.complete -or -not $taskManifest.complete -or -not (Test-Path -LiteralPath $taskSnapshot.launcher)) {
        throw 'Runtime snapshot is incomplete; client was not started.'
    }
    if ($PrepareOnly) {
        Write-Output ("Client launch prepared and verified without starting Minecraft: " + $taskSnapshot.snapshot)
        exit 0
    }
    # This is the official ModDevGradle launcher, with classes/resources/JAR and argument paths
    # frozen outside build/. Later compiles and clean operations cannot remove these live classes.
    & $env:ComSpec '/d' '/c' $taskSnapshot.launcher
    $taskExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $taskExitCode
