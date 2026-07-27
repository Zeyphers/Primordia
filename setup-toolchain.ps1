# Downloads a portable JDK 21 and Gradle into C:\Users\jacob.szczepaniak\dev\tools\.
# No admin rights required - everything is extracted from zips into user space.
# Safe to re-run: existing installs are detected and skipped.

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$toolsDir = 'C:\Users\jacob.szczepaniak\dev\tools'
$jdkDir   = Join-Path $toolsDir 'jdk-21'
$gradleVersion = '8.10'
$gradleDir = Join-Path $toolsDir "gradle-$gradleVersion"

if (-not (Test-Path $toolsDir)) { New-Item -ItemType Directory -Path $toolsDir -Force | Out-Null }

# ---- JDK 21 (Eclipse Temurin) ------------------------------------------------
if (Test-Path (Join-Path $jdkDir 'bin\java.exe')) {
    Write-Output "JDK already present at $jdkDir"
} else {
    $jdkZip = Join-Path $env:TEMP 'temurin-jdk21.zip'
    Write-Output 'Downloading Temurin JDK 21 (~190 MB)...'
    Invoke-WebRequest -UseBasicParsing `
        -Uri 'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse' `
        -OutFile $jdkZip

    Write-Output 'Extracting JDK...'
    $staging = Join-Path $env:TEMP 'jdk21-staging'
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
