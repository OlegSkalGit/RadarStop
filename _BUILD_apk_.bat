@echo off
setlocal enabledelayedexpansion
title Building RadarStop APK

echo ========================================================
echo   RadarStop - Ultra-Light Radar Detector Build
echo ========================================================
echo.

set "JDK_DIR=%USERPROFILE%\.jdk17"
set "JAVA_EXE=%JDK_DIR%\jdk-17.0.10+7\bin\java.exe"

if not exist "%JAVA_EXE%" (
    echo [1/3] Downloading OpenJDK 17...
    powershell -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.10%%2B7/OpenJDK17U-jdk_x64_windows_hotspot_17.0.10_7.zip' -OutFile '%%TEMP%%\jdk17.zip'"
    echo [1/3] Extracting OpenJDK 17...
    powershell -Command "Expand-Archive -Path '%%TEMP%%\jdk17.zip' -DestinationPath '%JDK_DIR%' -Force; Remove-Item '%%TEMP%%\jdk17.zip' -ErrorAction SilentlyContinue"
) else (
    echo [1/3] OpenJDK 17 is ready.
)

set "GRADLE_DIR=%USERPROFILE%\.gradle87"
set "GRADLE_BAT=%GRADLE_DIR%\gradle-8.7\bin\gradle.bat"

if not exist "%GRADLE_BAT%" (
    echo [2/3] Downloading Gradle 8.7...
    powershell -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-8.7-bin.zip' -OutFile '%%TEMP%%\gradle87.zip'"
    echo [2/3] Extracting Gradle 8.7...
    powershell -Command "Expand-Archive -Path '%%TEMP%%\gradle87.zip' -DestinationPath '%GRADLE_DIR%' -Force; Remove-Item '%%TEMP%%\gradle87.zip' -ErrorAction SilentlyContinue"
) else (
    echo [2/3] Gradle 8.7 is ready.
)

if "%ANDROID_HOME%"=="" (
    set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
)

echo [3/3] Building Release APK...
echo.

set "JAVA_HOME=%JDK_DIR%\jdk-17.0.10+7"
call "%GRADLE_BAT%" assembleRelease

echo.
for /f "tokens=*" %%V in ('powershell -Command "Get-Date -Format 'yy.MM.dd_HHmm'"') do set "VERSION_STR=%%V"
set "ROOT_APK=RadarStop_!VERSION_STR!.apk"

if exist "app\build\outputs\apk\release\app-release.apk" (
    copy /y "app\build\outputs\apk\release\app-release.apk" "!ROOT_APK!" >nul
    echo ========================================================
    echo   BUILD SUCCESSFUL - Signed and Ready to Install
    echo ========================================================
    echo   Copied to Root: !ROOT_APK!
    powershell -Command "$size = [math]::Round((Get-Item '!ROOT_APK!').Length / 1KB); Write-Host '  Size:' $size 'KB'"
    echo ========================================================
    goto END
)

if exist "app\build\outputs\apk\release\app-release-unsigned.apk" (
    copy /y "app\build\outputs\apk\release\app-release-unsigned.apk" "!ROOT_APK!" >nul
    echo ========================================================
    echo   BUILD SUCCESSFUL
    echo ========================================================
    echo   Copied to Root: !ROOT_APK!
    powershell -Command "$size = [math]::Round((Get-Item '!ROOT_APK!').Length / 1KB); Write-Host '  Size:' $size 'KB'"
    echo ========================================================
    goto END
)

echo [ERROR] Build failed or APK not found.

:END
