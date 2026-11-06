#!/usr/bin/env bash
# CI 环境准备（两个 job 共用）
#
# 不用 android-actions/setup-android@v3：它会去装早已下架的 `tools` 包，
# 在 runner 自带的 cmdline-tools 16 上直接报 "Failed to find package 'tools'" 并退出 1。
# ubuntu-latest 镜像自带 Android SDK，直接定位 sdkmanager 即可。

set -euo pipefail

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/usr/local/lib/android/sdk}}"
SDKMANAGER="$(command -v sdkmanager || true)"
if [ -z "$SDKMANAGER" ]; then
  SDKMANAGER="$(find "$SDK_ROOT" -path '*/cmdline-tools/*/bin/sdkmanager' | sort | tail -n1)"
fi
echo "SDK_ROOT   = $SDK_ROOT"
echo "SDKMANAGER = $SDKMANAGER"
"$SDKMANAGER" --version

# 把仓库里 android-37 相关的条目打进日志：哪天包名变了，红色 job 里
# 直接就能看到该把下面那行改成什么，不用再猜。
"$SDKMANAGER" --list | grep -E "platforms;android-37|build-tools;36" || true

yes | "$SDKMANAGER" --licenses > /dev/null || true
# 不需要 --channel=：API 37 已是正式版。
"$SDKMANAGER" "platforms;android-37.0" "build-tools;36.0.0" "platform-tools"

# 先换源：wrapper properties 指向腾讯镜像（国内网络），GitHub runner 在国外。
# 换完再让 setup-gradle 接手，它读的就是 wrapper properties。
sed -i 's#https://mirrors.cloud.tencent.com/gradle/#https://services.gradle.org/distributions/#g' \
  gradle/wrapper/gradle-wrapper.properties
grep '^distributionUrl' gradle/wrapper/gradle-wrapper.properties
