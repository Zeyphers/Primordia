# Downloads a portable JDK and Gradle into C:\Users\jacob.szczepaniak\dev\tools\.
# No admin rights required - everything is extracted from zips into user space.
# Safe to re-run: existing installs are detected and skipped.
#
# This branch targets Minecraft 26.2, which needs a different toolchain from the 1.21.1
# line on `main`:
#
#   |        | main (1.21.1) | this branch (26.2) |
#   |--------|---------------|--------------------|
#   | JDK    | 21            | 25 (26.2 requires it) |
#   | Gradle | 8.10          | 9.6.1 (Loom 1.17.17 needs >= 9.5.0) |
#
# Note that Fabric's own 26.1 notes say Gradle 9.4.0; that is too old for the current Loom,
# which declares a plugin API version of 9.5.0 and fails variant resolution against 9.4.

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$ProgressPreference = 'SilentlyContinue'   # Invoke-WebRequest is far faster without the progress bar

$toolsDir = 'C:\Users\jacob.szczepaniak\dev\tools'
$jdkVersion = '25'
$jdkDir   = Join-Path $toolsDir "jdk-$jdkVersion"
$gradleVersion = '9.6.1'
$gradleDir = Join-Path $toolsDir "gradle-$gradleVersion"

if (-not (Test-Path $toolsDir)) { New-Item -ItemType Directory -Path $toolsDir -Force | Out-Null }

# ---- JDK (Eclipse Temurin) ---------------------------------------------------
if (Test-Path (Join-Path $jdkDir 'bin\java.exe')) {
    Write-Output "JDK already present at $jdkDir"
} else {
    $jdkZip = Join-Path $env:TEMP "temurin-jdk$jdkVersion.zip"
    Write-Output "Downloading Temurin JDK $jdkVersion (~135 MB)..."
    Invoke-WebRequest -UseBasicParsing `
        -Uri "https://api.adoptium.net/v3/binary/latest/$jdkVersion/ga/windows/x64/jdk/hotspot/normal/eclipse" `
        -OutFile $jdkZip

    Write-Output 'Extracting JDK...'
    $staging = Join-Path $env:TEMP "jdk$jdkVersion-staging"
    if (Test-Path $staging) { Remove-Item $staging -Recurse -Force }
    Expand-Archive -Path $jdkZip -DestinationPath $staging -Force

    # The archive contains a single versioned root folder; flatten it to a stable path.
    $inner = Get-ChildItem $staging -Directory | Select-Object -First 1
    if (Test-Path $jdkDir) { Remove-Item $jdkDir -Recurse -Force }
    Move-Item $inner.FullName $jdkDir
    Remove-Item $staging -Recurse -Force
    Remove-Item $jdkZip -Force
    Write-Output "JDK installed at $jdkDir"
}

# ---- Gradle ------------------------------------------------------------------
if (Test-Path (Join-Path $gradleDir 'bin\gradle.bat')) {
    Write-Output "Gradle already present at $gradleDir"
} else {
    $gradleZip = Join-Path $env:TEMP "gradle-$gradleVersion-bin.zip"
    Write-Output "Downloading Gradle $gradleVersion (~130 MB)..."
    Invoke-WebRequest -UseBasicParsing `
        -Uri "https://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip" `
        -OutFile $gradleZip

    Write-Output 'Extracting Gradle...'
    Expand-Archive -Path $gradleZip -DestinationPath $toolsDir -Force
    Remove-Item $gradleZip -Force
    Write-Output "Gradle installed at $gradleDir"
}

Write-Output ''
Write-Output '--- versions ---'
& (Join-Path $jdkDir 'bin\java.exe') -version
$env:JAVA_HOME = $jdkDir
& (Join-Path $gradleDir 'bin\gradle.bat') --version --quiet 2>$null | Select-Object -First 6
