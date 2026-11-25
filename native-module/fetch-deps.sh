#!/bin/sh
# Downloads the official Zygisk module API header (not redistributed here).
set -e
cd "$(dirname "$0")"
mkdir -p third_party
URL="https://raw.githubusercontent.com/topjohnwu/zygisk-module-sample/master/module/jni/zygisk.hpp"
curl -fsSL "$URL" -o third_party/zygisk.hpp
grep -q "REGISTER_ZYGISK_MODULE" third_party/zygisk.hpp || { echo "zygisk.hpp looks wrong"; exit 1; }
echo "zygisk.hpp ready"
