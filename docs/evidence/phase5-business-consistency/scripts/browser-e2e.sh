#!/usr/bin/env bash
# Phase 5 浏览器回归使用当前打包产物、隔离测试库与全新租户夹具。
set -Eeuo pipefail
root="$(cd "$(dirname "$0")/../../../.." && pwd)"
cd "$root"
label="${1:-run}"
out="docs/evidence/phase5-business-consistency/e2e"
mkdir -p "$out/$label"
source .local/runtime.env
export no_proxy=127.0.0.1,localhost NO_PROXY=127.0.0.1,localhost
COMMERCE_DB_URL="$COMMERCE_TEST_DB_URL" COMMERCE_PORT=8606 java -jar commerce-app/target/commerce-app-0.1.0-SNAPSHOT.jar > "$out/$label-app.log" 2>&1 &
app=$!
cleanup() {
  kill "$app" 2>/dev/null || true
  wait "$app" 2>/dev/null || true
  # Spring 的临时开发口令不是业务证据，落盘前移除。
  python3 - "$out/$label-app.log" <<'PY'
from pathlib import Path
import re, sys
path = Path(sys.argv[1])
if path.exists():
    path.write_text(re.sub(r"Using generated security password: [^\n]+", "Using generated security password: [redacted]", path.read_text()))
PY
}
trap cleanup EXIT
for i in $(seq 1 90); do
  if curl -sf http://127.0.0.1:8606/actuator/health >/dev/null 2>&1; then break; fi
  sleep 1
done
export COMMERCE_E2E_SCHEMA=commerce_test_20260923 COMMERCE_E2E_BASE_URL=http://127.0.0.1:8606
python3 scripts/prepare-e2e.py
python3 scripts/seed-member-suite.py --fresh
python3 scripts/seed-operations.py --fresh
cd frontend
if COMMERCE_UI_URL=http://127.0.0.1:8606 COMMERCE_EVIDENCE_DIR="../$out/$label" npx playwright test > "../$out/$label-browser.log" 2>&1; then
  code=0
else
  code=$?
fi
tail -20 "../$out/$label-browser.log"
exit "$code"
