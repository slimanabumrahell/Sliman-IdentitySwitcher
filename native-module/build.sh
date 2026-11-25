#!/bin/bash
# Local build: ANDROID_NDK_HOME must point to an NDK (r25+). Produces Sliman-Native-Zygisk.zip
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME}"
[ -f third_party/zygisk.hpp ] || ./fetch-deps.sh

rm -rf out && mkdir -p out/zip/zygisk
declare -A ABI_DIR=( [arm64-v8a]=arm64-v8a [armeabi-v7a]=armeabi-v7a [x86_64]=x86_64 [x86]=x86 )
for abi in arm64-v8a armeabi-v7a x86_64 x86; do
  cmake -S . -B "out/build-$abi" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release
  cmake --build "out/build-$abi"
  cp "out/build-$abi/libsliman_native.so" "out/zip/zygisk/${ABI_DIR[$abi]}.so"
done

cp -r module/. out/zip/
( cd out/zip && zip -r -9 ../Sliman-Native-Zygisk.zip . -x '*.DS_Store' )
echo "Built: $(pwd)/out/Sliman-Native-Zygisk.zip"
