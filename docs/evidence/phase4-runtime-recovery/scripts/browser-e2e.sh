#!/usr/bin/env bash
# 浏览器回归：打包jar以测试库运行在8606，按第三阶段相同方式准备夹具后执行全部Playwright用例。
# 用法: browser-e2e.sh <label>   结果写入 docs/evidence/phase4-runtime-recovery/e2e/<label>-*.{log,json}
set -Euo pipefail
root="$(cd "$(dirname "$0")/../../../.." && pwd)"; cd "$root"
label="${1:-run}"; out="docs/evidence/phase4-runtime-recovery/e2e"; mkdir -p "$out"
source .local/runtime.env
export no_proxy=127.0.0.1,localhost NO_PROXY=127.0.0.1,localhost
COMMERCE_DB_URL="$COMMERCE_TEST_DB_URL" COMMERCE_PORT=8606 java -jar commerce-app/target/commerce-app-0.1.0-SNAPSHOT.jar > "$out/$label-app.log" 2>&1 &
app=$!; trap 'kill $app 2>/dev/null; wait $app 2>/dev/null' EXIT
for i in $(seq 1 90); do curl -sf http://127.0.0.1:8606/actuator/health >/dev/null 2>&1 && break; sleep 1; done
export COMMERCE_E2E_SCHEMA=commerce_test_20260923 COMMERCE_E2E_BASE_URL=http://127.0.0.1:8606
python3 scripts/prepare-e2e.py && python3 scripts/seed-member-suite.py --fresh && python3 scripts/seed-operations.py --fresh || { echo "fixture setup failed"; exit 2; }
cd frontend && COMMERCE_UI_URL=http://127.0.0.1:8606 COMMERCE_EVIDENCE_DIR="../$out/$label" npx playwright test > "../$out/$label-browser.log" 2>&1
code=$?; echo "playwright exit=$code"; tail -12 "../$out/$label-browser.log"
