$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$androidDir = Join-Path $repoRoot 'android'
$wrapper = Join-Path $androidDir 'gradlew.bat'
$apkSource = Join-Path $androidDir 'app\build\outputs\apk\debug\app-debug.apk'
$apkDirectory = Join-Path $repoRoot 'dist'
$apkDestination = Join-Path $apkDirectory 'Geldmeister.apk'

if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
    throw 'Android SDK not found. Set ANDROID_HOME or ANDROID_SDK_ROOT, and install Android SDK Platform 35 / Build Tools 35.0.0.'
}

& $wrapper -p $androidDir --no-daemon assembleDebug
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

New-Item -ItemType Directory -Path $apkDirectory -Force | Out-Null
Copy-Item -LiteralPath $apkSource -Destination $apkDestination -Force
Write-Host "APK created: $apkDestination"
