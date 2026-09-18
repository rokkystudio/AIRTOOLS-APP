param(
    [string]$HashcatRootDir = '',
    [string]$ProjectDir = '',
    [string]$AndroidAbi = 'arm64-v8a',
    [bool]$AssembleApk = $true
)

$ErrorActionPreference = 'Stop'
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8NoBom
[Console]::OutputEncoding = $utf8NoBom
$OutputEncoding = $utf8NoBom

if ([string]::IsNullOrWhiteSpace($ProjectDir)) {
    $ProjectDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
} else {
    $ProjectDir = (Resolve-Path $ProjectDir).Path
}

if ([string]::IsNullOrWhiteSpace($HashcatRootDir)) {
    $HashcatRootDir = [System.IO.Path]::GetFullPath((Join-Path $ProjectDir '..\HASHCAT'))
} else {
    $HashcatRootDir = (Resolve-Path $HashcatRootDir).Path
}

$BuildAbiDir = Join-Path (Join-Path $HashcatRootDir 'build') $AndroidAbi
$HashcatModuleDir = Join-Path $ProjectDir 'hashcat'
$GeneratedRuntimeDir = Join-Path $HashcatModuleDir 'build\generated\hashcatRuntime'
$JniDir = Join-Path $GeneratedRuntimeDir "jniLibs\$AndroidAbi"
$AssetsDir = Join-Path $GeneratedRuntimeDir 'assets\hashcat'

if (-not (Test-Path $BuildAbiDir)) { throw "HASHCAT ABI build output is missing: $BuildAbiDir" }
if (-not (Test-Path $HashcatModuleDir)) { throw "AIRTOOLS hashcat module is missing: $HashcatModuleDir" }

if (Test-Path $GeneratedRuntimeDir) { Remove-Item -Recurse -Force -Path $GeneratedRuntimeDir }
New-Item -ItemType Directory -Force -Path $JniDir | Out-Null
New-Item -ItemType Directory -Force -Path $AssetsDir | Out-Null

$frontend = Join-Path $BuildAbiDir 'hashcat'
if (-not (Test-Path $frontend)) { throw "hashcat frontend is missing in build output: $frontend" }
Copy-Item -Force -Path $frontend -Destination (Join-Path $JniDir 'libhashcat_exec.so')

foreach ($name in @('modules', 'bridges', 'feeds', 'OpenCL', 'rules', 'tunings', 'pcfg')) {
    $src = Join-Path $BuildAbiDir $name
    $dst = Join-Path $AssetsDir $name
    if (-not (Test-Path $src)) { throw "HASHCAT runtime asset directory is missing in build output: $src" }
    Copy-Item -Recurse -Force -Path $src -Destination $dst
}

$hcstat = Join-Path $BuildAbiDir 'hashcat.hcstat2'
if (Test-Path $hcstat) { Copy-Item -Force -Path $hcstat -Destination (Join-Path $AssetsDir 'hashcat.hcstat2') }

if ($AssembleApk) {
    Push-Location $ProjectDir
    try {
        & (Join-Path $ProjectDir 'gradlew.bat') assembleDebug
        if ($LASTEXITCODE -ne 0) { throw "Gradle assembleDebug failed with exit code $LASTEXITCODE" }
    }
    finally { Pop-Location }
}

$apk = Join-Path $ProjectDir 'app\build\outputs\apk\debug\app-debug.apk'
$result = [ordered]@{
    HashcatRoot = $HashcatRootDir
    AndroidAbi = $AndroidAbi
    Build = $BuildAbiDir
    GeneratedRuntime = $GeneratedRuntimeDir
    Lib = Join-Path $JniDir 'libhashcat_exec.so'
    LibBytes = (Get-Item (Join-Path $JniDir 'libhashcat_exec.so')).Length
    Modules = (Get-ChildItem (Join-Path $AssetsDir 'modules') -Filter '*.so' -File | Measure-Object).Count
    Bridges = (Get-ChildItem (Join-Path $AssetsDir 'bridges') -Filter '*.so' -File | Measure-Object).Count
    Feeds = (Get-ChildItem (Join-Path $AssetsDir 'feeds') -Filter '*.so' -File | Measure-Object).Count
    OpenCL = (Get-ChildItem (Join-Path $AssetsDir 'OpenCL') -Recurse -File | Measure-Object).Count
}
if (Test-Path $apk) {
    $result['Apk'] = $apk
    $result['ApkBytes'] = (Get-Item $apk).Length
}
[pscustomobject]$result | Format-List