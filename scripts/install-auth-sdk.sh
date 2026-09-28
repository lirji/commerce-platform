#!/usr/bin/env bash
set -Eeuo pipefail
cd "$(dirname "$0")/.."
auth_source="${AUTH_SDK_SOURCE_DIR:-../auth-platform}"
auth_ref="$(cat scripts/auth-sdk-source.ref)"
# 编译当前源码前先核对已批准SDK范围，不能把任意本机SNAPSHOT当成指定制品。
git -C "$auth_source" cat-file -e "$auth_ref^{commit}"
git -C "$auth_source" diff --exit-code "$auth_ref" -- pom.xml auth-platform-protocol auth-platform-sdk
# 使用与商城相同的Maven和本地仓库设置，避免wrapper与系统Maven安装到不同仓库。
mvn -q -f "$auth_source/pom.xml" -pl auth-platform-sdk -am install -DskipTests
