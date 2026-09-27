#!/usr/bin/env bash
# 使用新打包应用和隔离测试库，仅验证受 Phase 6 影响的真实浏览器旅程。
set -Eeuo pipefail
root="$(cd "$(dirname "$0")/../../../.." && pwd)"
cd "$root"
source .local/runtime.env
out="$root/.local/phase6-browser"
mkdir -p "$out"
export no_proxy=127.0.0.1,localhost NO_PROXY=127.0.0.1,localhost
COMMERCE_DB_URL="$COMMERCE_TEST_DB_URL" COMMERCE_PORT=8607 java -jar commerce-app/target/commerce-app-0.1.0-SNAPSHOT.jar > "$out/app.log" 2>&1 &
app=$!
cleanup() {
  kill "$app" 2>/dev/null || true
  wait "$app" 2>/dev/null || true
  python3 - "$out/app.log" <<'PY'
from pathlib import Path
import re, sys
path = Path(sys.argv[1])
if path.exists():
    path.write_text(re.sub(r"Using generated security password: [^\n]+", "Using generated security password: [redacted]", path.read_text()))
PY
}
trap cleanup EXIT
for i in $(seq 1 90); do
  if curl -sf http://127.0.0.1:8607/actuator/health >/dev/null 2>&1; then break; fi
  sleep 1
done
export COMMERCE_E2E_SCHEMA=commerce_test_20260923 COMMERCE_E2E_BASE_URL=http://127.0.0.1:8607
python3 scripts/prepare-e2e.py
python3 scripts/seed-member-suite.py --fresh
python3 scripts/seed-operations.py --fresh
cd frontend
COMMERCE_UI_URL=http://127.0.0.1:8607 COMMERCE_EVIDENCE_DIR="$out" \
  npx playwright test --grep '真实浏览器：会员下单|可视规则与低代码页面预览' > "$out/browser.log" 2>&1
tail -30 "$out/browser.log"
