#!/usr/bin/env bash
set -Eeuo pipefail
cd "$(dirname "$0")/.."
# 项目只有无状态应用；不删除任何数据卷或共享基础设施。
docker compose --env-file .local/compose.env down
