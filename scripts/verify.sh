#!/usr/bin/env bash
set -Eeuo pipefail
cd "$(dirname "$0")/.."
if [[ -f .local/runtime.env ]]; then source .local/runtime.env; fi
exec mvn -B verify "$@"
