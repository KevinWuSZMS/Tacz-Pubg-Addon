param(
    [string[]]$GradleTasks = @('build')
)

$ErrorActionPreference = 'Stop'
$workspacePath = Split-Path -Parent $PSScriptRoot
$voxySourcePath = Join-Path $workspacePath 'external\voxy-1.21.1'
$jdkPath = 'C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot'
$socketPath = Join-Path $workspacePath '.jdk-tmp'
$logPath = Join-Path $workspacePath 'logs\voxy-build.log'
if (-not (Test-Path -LiteralPath (Join-Path $voxySourcePath 'gradlew.bat'))) {
    throw "Voxy source is missing: $voxySourcePath"
}
New-Item -ItemType Directory -Path $socketPath -Force | Out-Null
$savedJavaHome = $env:JAVA_HOME
$savedJavaToolOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_HOME = $jdkPath
    $env:JAVA_TOOL_OPTIONS = (($savedJavaToolOptions + ' -Djdk.net.unixdomain.tmpdir=' + $socketPath + ' -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=9674 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=9674').Trim())
    Push-Location -LiteralPath $voxySourcePath
    try {
        # Skip downloading/remapping third-party sources; Voxy's own sources still compile normally.
        & .\gradlew.bat --console=plain --max-workers=4 -Dfabric.loom.ci=true @GradleTasks 2>&1 | Tee-Object -FilePath $logPath
        if ($LASTEXITCODE -ne 0) { throw "Voxy build failed; see $logPath" }
    } finally {
        Pop-Location
    }
} finally {
    $env:JAVA_HOME = $savedJavaHome
    $env:JAVA_TOOL_OPTIONS = $savedJavaToolOptions
}
