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
# 注意：本文件必须保存为 UTF-8 with BOM —— Windows PowerShell 5.1 对无 BOM 的
# .ps1 按 ANSI(GBK) 解码，中文输出会乱码并报解析错误。
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
    Write-Host '[build-release] 正式版(release)必须签名，但以下签名环境变量在当前会话和注册表 HKCU:\Environment 中都找不到：' -ForegroundColor Red
    foreach ($name in $missing) { Write-Host "  - $name" -ForegroundColor Red }
    Write-Host ''
    Write-Host '本次构建已跳过（避免浪费几分钟产出未签名 APK）。' -ForegroundColor Yellow
    Write-Host '解决方法：设置用户级环境变量（设置 → 系统 → 关于 → 高级系统设置 → 环境变量，或注册表 HKCU:\Environment）后，' -ForegroundColor Yellow
    Write-Host '重开终端再运行本脚本。已设置的变量本脚本会自动从注册表读取，无需手动同步。' -ForegroundColor Yellow
    Write-Host ''
    exit 1
}

if ($loadedFromRegistry.Count -gt 0) {
    Write-Host ("[build-release] 以下变量从注册表 HKCU:\Environment 读取（当前进程环境缺失）：" + ($loadedFromRegistry -join ', '))
}
Write-Host '[build-release] 签名环境变量齐全，开始构建正式版…'

& $PSScriptRoot\gradlew.bat :app:assembleRelease
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$apk = Get-ChildItem "$PSScriptRoot\app\build\outputs\apk\release\*.apk" | Select-Object -First 1
if (-not $apk) {
    Write-Host '[build-release] 构建成功但找不到 APK 产物。' -ForegroundColor Red
    exit 1
}

# Build success != signed. The only trustworthy check is apksigner.
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'D:\dev\android_sdk' }
$apksigner = Get-ChildItem "$sdkRoot\build-tools\*\apksigner.bat" | Sort-Object { [version]($_.Directory.Name) } -Descending | Select-Object -First 1
if (-not $apksigner) {
    Write-Host "[build-release] 找不到 apksigner（$sdkRoot\build-tools\*），请手动验签。" -ForegroundColor Yellow
    exit 0
}

& $apksigner.FullName verify --print-certs -v $apk.FullName
if ($LASTEXITCODE -ne 0) {
    Write-Host ''
    Write-Host "[build-release] 构建完成但验签失败：$($apk.Name) 未签名或签名无效，禁止交付！" -ForegroundColor Red
    exit 1
}

Write-Host ''
Write-Host "[build-release] 完成：$($apk.Name) 已签名并通过验签。" -ForegroundColor Green
