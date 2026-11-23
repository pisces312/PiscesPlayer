# Release build wrapper: fails fast when the signing environment variables are
# unavailable, instead of silently producing an UNSIGNED release APK after a
# full Gradle build.
#
# The KEY_* variables are user-level (registry HKCU:\Environment). Processes
# started before the variables were set inherit an old environment, so long-
# running sessions (DSH, IDEs) may not see them even though they are set. This
# script therefore falls back to reading the registry directly; if a variable
# is missing there too, it prints setup guidance and skips the build entirely.
#
# Usage:  .\build-release.bat        (or)  powershell -File .\build-release.ps1
#
# Note: keep this file ASCII/English. The previous version had Chinese strings
# and required a UTF-8 BOM so Windows PowerShell 5.1 would not mis-decode it;
# English-only removes that whole class of encoding problem.
# Requires: pwsh on PATH, Android SDK at $env:ANDROID_HOME or the fallback below.

$ErrorActionPreference = 'Stop'

$requiredVars = 'KEY_STORE', 'KEY_STORE_LOCATION', 'KEY_STORE_PASSWORD', 'KEY_ALIAS', 'KEY_PASSWORD'

# Pull any missing variable from the user registry (HKCU:\Environment).
$loadedFromRegistry = @()
foreach ($name in $requiredVars) {
    if (-not [Environment]::GetEnvironmentVariable($name, 'Process')) {
        $userValue = [Environment]::GetEnvironmentVariable($name, 'User')
        if ($userValue) {
            Set-Item -Path "env:$name" -Value $userValue
            $loadedFromRegistry += $name
        }
    }
}

$missing = @($requiredVars | Where-Object { -not [Environment]::GetEnvironmentVariable($_, 'Process') })

if ($missing.Count -gt 0) {
    Write-Host ''
    Write-Host '[build-release] A signed release is required, but these signing environment variables are missing from both the current session and the HKCU:\Environment registry:' -ForegroundColor Red
    foreach ($name in $missing) { Write-Host "  - $name" -ForegroundColor Red }
    Write-Host ''
    Write-Host 'Build skipped (avoids wasting minutes producing an UNSIGNED APK).' -ForegroundColor Yellow
    Write-Host 'Fix: set the user-level variables (Settings > System > About > Advanced system settings > Environment Variables, or registry HKCU:\Environment), then re-run.' -ForegroundColor Yellow
    Write-Host 'Already-set variables are read automatically from the registry; no manual sync needed.' -ForegroundColor Yellow
    Write-Host ''
    exit 1
}

if ($loadedFromRegistry.Count -gt 0) {
    Write-Host ("[build-release] Read from HKCU:\Environment (missing in the current process): " + ($loadedFromRegistry -join ', '))
}
Write-Host '[build-release] Signing variables present; building the signed release...'

& $PSScriptRoot\gradlew.bat :app:assembleRelease
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$apk = Get-ChildItem "$PSScriptRoot\app\build\outputs\apk\release\*.apk" | Select-Object -First 1
if (-not $apk) {
    Write-Host '[build-release] Build succeeded but no APK artifact was found.' -ForegroundColor Red
    exit 1
}

# Build success != signed. The only trustworthy check is apksigner.
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'D:\dev\android_sdk' }
# Resolve to a sorted list first, then index it. Piping Get-ChildItem straight
# into `Select-Object -First 1` under $ErrorActionPreference='Stop' can hang:
# the pipeline's early-exit path surfaces a terminating error that never unwinds.
$apksigners = @(
    Get-ChildItem "$sdkRoot\build-tools\*\apksigner.bat" -ErrorAction SilentlyContinue |
        Sort-Object { [version]($_.Directory.Name) } -Descending
)
$apksigner = if ($apksigners.Count -gt 0) { $apksigners[0] } else { $null }
if (-not $apksigner) {
    Write-Host "[build-release] apksigner not found under $sdkRoot\build-tools\*; please verify the signature manually." -ForegroundColor Yellow
    exit 0
}

& $apksigner.FullName verify --print-certs -v $apk.FullName
if ($LASTEXITCODE -ne 0) {
    Write-Host ''
    Write-Host "[build-release] Build finished but signature verification FAILED: $($apk.Name) is unsigned or invalid. Do NOT ship it." -ForegroundColor Red
    exit 1
}

Write-Host ''
Write-Host "[build-release] Done: $($apk.Name) is signed and verified." -ForegroundColor Green