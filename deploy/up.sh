#!/usr/bin/env bash
set -Eeuo pipefail
cd "$(dirname "$0")/.."
python3 deploy/configure-local.py
docker compose --env-file .local/compose.env config --quiet
docker compose --env-file .local/compose.env up -d --build --wait --wait-timeout 180
