<#
.SYNOPSIS
    Builds the Aikyam Android companion app locally and optionally installs it to a connected device.

.PARAMETER Release
    Build a release APK instead of debug.

.PARAMETER Clean
    Perform a clean before building.

.PARAMETER Install
    Automatically install the built APK to the target Android device via ADB.

.PARAMETER DeviceSerial
    Specific ADB serial/IP to install to (defaults to 192.168.1.109:5555 if online, or the first connected device).
#>
param (
    [switch]$Release,
    [switch]$Clean,
    [switch]$Install,
    [string]$DeviceSerial = "192.168.1.109:5555"
)

$ErrorActionPreference = "Stop"

# Auto-detect JAVA_HOME
$JavaCandidates = @(
    "C:\Program Files\Android\Android Studio\jbr",
    "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.1.1.1\jbr",
    $env:JAVA_HOME
)

$FoundJava = $null
foreach ($c in $JavaCandidates) {
    if ($c -and (Test-Path "$c\bin\java.exe")) {
        $FoundJava = $c
        break
    }
}

if (-not $FoundJava) {
    Write-Error "Could not find a valid JDK. Please ensure Android Studio JBR or JDK 21+ is installed."
    exit 1
}

$env:JAVA_HOME = $FoundJava
Write-Host "Using JAVA_HOME: $env:JAVA_HOME" -ForegroundColor Green

# Auto-detect ANDROID_HOME
$SdkCandidates = @(
    "C:\Users\HP\AppData\Local\Android\Sdk",
    $env:ANDROID_HOME,
    $env:ANDROID_SDK_ROOT
)

$FoundSdk = $null
foreach ($s in $SdkCandidates) {
    if ($s -and (Test-Path $s)) {
        $FoundSdk = $s
        break
    }
}

if (-not $FoundSdk) {
    Write-Error "Could not find Android SDK. Please ensure Android SDK is installed."
    exit 1
}

$env:ANDROID_HOME = $FoundSdk
$env:ANDROID_SDK_ROOT = $FoundSdk
Write-Host "Using ANDROID_HOME: $env:ANDROID_HOME" -ForegroundColor Green

# Setup PATH
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"

$RepoRoot = Resolve-Path "$PSScriptRoot\.."
Set-Location $RepoRoot

$BuildType = if ($Release) { "Release" } else { "Debug" }
$Task = ":app:assemble$BuildType"

if ($Clean) {
    Write-Host "Cleaning..." -ForegroundColor Cyan
    & ".\gradlew.bat" clean
}

Write-Host "Building Aikyam ($BuildType APK)..." -ForegroundColor Cyan
$Stopwatch = [System.Diagnostics.Stopwatch]::StartNew()

& ".\gradlew.bat" $Task --no-daemon

$Stopwatch.Stop()
Write-Host ("Build completed in {0:N1} seconds." -f $Stopwatch.Elapsed.TotalSeconds) -ForegroundColor Green

# Locate built APK
$BuildTypeLower = $BuildType.ToLower()
$ApkDir = "$RepoRoot\app\build\outputs\apk\$BuildTypeLower"
$Apks = Get-ChildItem -Path $ApkDir -Filter "*.apk" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending

if (-not $Apks -or $Apks.Count -eq 0) {
    Write-Warning "APK was built but could not be located in $ApkDir"
    exit 0
}

$Apk = $Apks[0]
Write-Host "Generated APK: $($Apk.FullName) ($([math]::Round($Apk.Length / 1MB, 2)) MB)" -ForegroundColor Yellow

# Install if requested
if ($Install) {
    $Adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
    if (-not (Test-Path $Adb)) {
        $Adb = "adb.exe"
    }

    # Auto-detect active device if available
    $deviceLines = & $Adb devices
    foreach ($dl in $deviceLines) {
        if ($dl -match '(\S+)\s+device$') {
            $DeviceSerial = $matches[1]
            break
        }
    }
    Write-Host "Using active ADB device: $DeviceSerial" -ForegroundColor Cyan

    Write-Host "Deploying Sefirah AI APK to device ($DeviceSerial)..." -ForegroundColor Cyan
    & $Adb -s $DeviceSerial install -r $Apk.FullName
    if ($LASTEXITCODE -eq 0) {
        Write-Host "Sefirah AI APK successfully installed on $DeviceSerial!" -ForegroundColor Green
    } else {
        Write-Warning "ADB install failed with exit code $LASTEXITCODE. Trying default device..."
        & $Adb install -r $Apk.FullName
    }
}
