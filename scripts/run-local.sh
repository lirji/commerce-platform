#!/usr/bin/env bash
set -Eeuo pipefail
cd "$(dirname "$0")/.."
source .local/runtime.env
exec java -jar commerce-app/target/commerce-app-0.1.0-SNAPSHOT.jar
