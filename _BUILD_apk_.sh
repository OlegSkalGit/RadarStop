#!/usr/bin/env bash
set -e

echo "========================================================"
echo "  RadarStop - Ultra-Light Radar Detector Build"
echo "========================================================"
echo ""

OS="$(uname -s)"
ARCH="$(uname -m)"

case "${OS}" in
    Linux*)     PLATFORM="linux";;
    Darwin*)    PLATFORM="mac";;
    *)          PLATFORM="unknown";;
esac

if [ "${PLATFORM}" = "unknown" ]; then
    echo "[ERROR] Unsupported OS: ${OS}"
    exit 1
fi

JDK_DIR="${HOME}/.jdk17"
JAVA_BIN="${JDK_DIR}/jdk-17.0.10+7/bin/java"
if [ "${PLATFORM}" = "mac" ] && [ -d "${JDK_DIR}/jdk-17.0.10+7/Contents/Home" ]; then
    JAVA_BIN="${JDK_DIR}/jdk-17.0.10+7/Contents/Home/bin/java"
fi

if [ ! -f "${JAVA_BIN}" ]; then
    echo "[1/3] Downloading OpenJDK 17 for ${PLATFORM} (${ARCH})..."
    mkdir -p "${JDK_DIR}"
    
    if [ "${PLATFORM}" = "linux" ]; then
        if [ "${ARCH}" = "aarch64" ] || [ "${ARCH}" = "arm64" ]; then
            JDK_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.10%2B7/OpenJDK17U-jdk_aarch64_linux_hotspot_17.0.10_7.tar.gz"
        else
            JDK_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.10%2B7/OpenJDK17U-jdk_x64_linux_hotspot_17.0.10_7.tar.gz"
        fi
        curl -sL "${JDK_URL}" | tar -xz -C "${JDK_DIR}"
    elif [ "${PLATFORM}" = "mac" ]; then
        if [ "${ARCH}" = "arm64" ] || [ "${ARCH}" = "aarch64" ]; then
            JDK_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.10%2B7/OpenJDK17U-jdk_aarch64_mac_hotspot_17.0.10_7.tar.gz"
        else
            JDK_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.10%2B7/OpenJDK17U-jdk_x64_mac_hotspot_17.0.10_7.tar.gz"
        fi
        curl -sL "${JDK_URL}" | tar -xz -C "${JDK_DIR}"
    fi
else
    echo "[1/3] OpenJDK 17 is ready."
fi

if [ -d "${JDK_DIR}/jdk-17.0.10+7/Contents/Home" ]; then
    export JAVA_HOME="${JDK_DIR}/jdk-17.0.10+7/Contents/Home"
else
    export JAVA_HOME="${JDK_DIR}/jdk-17.0.10+7"
fi

GRADLE_DIR="${HOME}/.gradle87"
GRADLE_BIN="${GRADLE_DIR}/gradle-8.7/bin/gradle"

if [ ! -f "${GRADLE_BIN}" ]; then
    echo "[2/3] Downloading Gradle 8.7..."
    mkdir -p "${GRADLE_DIR}"
    TMP_ZIP="/tmp/gradle87.zip"
    curl -sL "https://services.gradle.org/distributions/gradle-8.7-bin.zip" -o "${TMP_ZIP}"
    unzip -q "${TMP_ZIP}" -d "${GRADLE_DIR}"
    rm -f "${TMP_ZIP}"
    chmod +x "${GRADLE_BIN}"
else
    echo "[2/3] Gradle 8.7 is ready."
fi

if [ -z "${ANDROID_HOME}" ]; then
    if [ "${PLATFORM}" = "mac" ] && [ -d "${HOME}/Library/Android/sdk" ]; then
        export ANDROID_HOME="${HOME}/Library/Android/sdk"
    elif [ -d "${HOME}/Android/Sdk" ]; then
        export ANDROID_HOME="${HOME}/Android/Sdk"
    fi
fi

echo "[3/3] Building Release APK..."
echo ""

"${GRADLE_BIN}" assembleRelease

APK_PATH="app/build/outputs/apk/release/app-release.apk"
if [ ! -f "${APK_PATH}" ]; then
    APK_PATH="app/build/outputs/apk/release/app-release-unsigned.apk"
fi

if [ -f "${APK_PATH}" ]; then
    VERSION_STR=$(date +"%y.%m.%d_%H%M")
    DEST_APK="RadarStop_${VERSION_STR}.apk"
    cp -f "${APK_PATH}" "${DEST_APK}"

    echo "========================================================"
    echo "  BUILD SUCCESSFUL! (Ready to Install)"
    echo "========================================================"
    echo "  Copied to Root: ${DEST_APK}"
    SIZE_KB=$(du -k "${DEST_APK}" | cut -f1)
    echo "  Size: ${SIZE_KB} KB"
    echo "========================================================"
else
    echo "[ERROR] Build failed or APK not found."
    exit 1
fi
