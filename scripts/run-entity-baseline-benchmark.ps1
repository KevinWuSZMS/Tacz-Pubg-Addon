param([int]$TimeoutSeconds = 480, [ValidateSet(-1, 0, 25, 75, 100)][int]$Percent = -1,
    [ValidateSet(-1, 0, 25, 75, 100)][int]$StartPercent = -1)
$ErrorActionPreference = 'Stop'
$benchmarkWorkspace = Split-Path -Parent $PSScriptRoot
$benchmarkProject = Join-Path $benchmarkWorkspace 'neoforge-1.21.1'
$benchmarkRun = Join-Path $benchmarkProject 'run-entity-benchmark'
$benchmarkLog = Join-Path $benchmarkWorkspace 'logs/large-map-entity-benchmark.log'
$benchmarkErrorLog = Join-Path $benchmarkWorkspace 'logs/large-map-entity-benchmark-stderr.log'
$benchmarkJava = (Get-Command java.exe).Source
New-Item -ItemType Directory -Force (Split-Path $benchmarkLog), $benchmarkRun | Out-Null
foreach ($benchmarkPreviousLog in @($benchmarkLog, $benchmarkErrorLog)) {
    if (Test-Path -LiteralPath $benchmarkPreviousLog) {
        Move-Item -LiteralPath $benchmarkPreviousLog -Destination ($benchmarkPreviousLog + '.' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    }
}
$benchmarkVm = Join-Path $benchmarkProject 'build/moddev/gameTestServerRunVmArgs.txt'
if (-not ((Get-Content -LiteralPath $benchmarkVm) -match 'entityBenchmark=true')) {
    throw 'Prepare the benchmark first: gradlew --offline -PpubgInventoryGameTests -PpubgEntityBenchmark prepareGameTestServerRun'
}
$benchmarkFolders = @('build/classes/java/main', 'build/resources/main', 'build/classes/java/test', 'build/resources/test') |
    ForEach-Object { 'tacz_pubg%%' + (Join-Path $benchmarkProject $_).Replace('\', '/') }
$benchmarkArguments = @('-Xms512m', '-Xmx4g', ('@' + (Join-Path $benchmarkProject 'build/moddev/serverRunClasspath.txt')),
    ('@' + $benchmarkVm), ('-Dtacz_pubg.entityBenchmark.percent=' + $Percent),
    ('-Dtacz_pubg.entityBenchmark.startPercent=' + $StartPercent), ('-Dfml.modFolders=' + ($benchmarkFolders -join ';')),
    'net.neoforged.devlaunch.Main', ('@' + (Join-Path $benchmarkProject 'build/moddev/gameTestServerRunProgramArgs.txt')))
$benchmarkProcess = Start-Process -FilePath $benchmarkJava -ArgumentList $benchmarkArguments -WorkingDirectory $benchmarkRun `
    -WindowStyle Hidden -PassThru -RedirectStandardOutput $benchmarkLog -RedirectStandardError $benchmarkErrorLog
Write-Output ('ENTITY_BENCH_LAUNCH pid=' + $benchmarkProcess.Id + ' watchdog_seconds=' + $TimeoutSeconds + ' log=' + $benchmarkLog)
try {
    if (-not $benchmarkProcess.WaitForExit($TimeoutSeconds * 1000)) {
        # Only this exact Java process and its descendants; never stop unrelated clients/Gradle.
        $benchmarkProcess.Kill($true)
        $benchmarkProcess.WaitForExit()
        throw ('Entity benchmark watchdog stopped PID ' + $benchmarkProcess.Id)
    }
    Write-Output ('ENTITY_BENCH_EXIT pid=' + $benchmarkProcess.Id + ' exit_code=' + $benchmarkProcess.ExitCode)
    if ($benchmarkProcess.ExitCode -ne 0) { throw ('Benchmark failed; inspect ' + $benchmarkLog + ' and ' + $benchmarkErrorLog) }
} finally {
    if (-not $benchmarkProcess.HasExited) { $benchmarkProcess.Kill($true) }
}
