param()

$ErrorActionPreference = 'Stop'
$workspacePath = Split-Path -Parent $PSScriptRoot
$jdkPath = 'C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot'
$cachePath = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1\org.lwjgl'
$outputPath = Join-Path $workspacePath 'logs\blue-zone-lod-gpu-checks'
$sourcePath = Join-Path $workspacePath 'neoforge-1.21.1\src\test\java\com\example\examplemod\zones\client\visual\BlueZoneLodGpuChecks.java'
$shaderPath = Join-Path $workspacePath 'neoforge-1.21.1\src\main\resources\assets\tacz_pubg\shaders\core'
$jars = foreach ($moduleName in @('lwjgl', 'lwjgl-glfw', 'lwjgl-opengl')) {
    foreach ($suffix in @('', '-natives-windows')) {
        $fileName = "$moduleName-3.3.3$suffix.jar"
        $versionPath = Join-Path $cachePath "$moduleName\3.3.3"
        $found = Get-ChildItem -LiteralPath $versionPath -Recurse -File -Filter $fileName |
            Sort-Object FullName | Select-Object -First 1
        if ($null -eq $found) { throw "Existing local LWJGL dependency missing: $fileName" }
        $found.FullName
    }
}
New-Item -ItemType Directory -Path $outputPath -Force | Out-Null
$classpath = $jars -join [IO.Path]::PathSeparator
& (Join-Path $jdkPath 'bin\javac.exe') -encoding UTF-8 --release 21 -cp $classpath -d $outputPath $sourcePath
if ($LASTEXITCODE -ne 0) { throw 'Blue-zone GPU check compilation failed' }
& (Join-Path $jdkPath 'bin\java.exe') -cp ($outputPath + [IO.Path]::PathSeparator + $classpath) `
    com.example.examplemod.zones.client.visual.BlueZoneLodGpuChecks $shaderPath
if ($LASTEXITCODE -ne 0) { throw 'Blue-zone GPU checks failed; see the preceding context or assertion diagnostic' }
