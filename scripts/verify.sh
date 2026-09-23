#!/usr/bin/env bash
set -Eeuo pipefail
cd "$(dirname "$0")/.."
source .local/runtime.env
exec mvn -B verify "$@"
