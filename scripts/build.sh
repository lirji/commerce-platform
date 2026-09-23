#!/usr/bin/env bash
set -Eeuo pipefail
cd "$(dirname "$0")/.."
if [[ -f .local/runtime.env ]]; then source .local/runtime.env; fi
: "${COMMERCE_TEST_DB_URL:?An isolated project test database is required}"
npm ci --prefix frontend --ignore-scripts
npm run build --prefix frontend
test -s frontend/dist/index.html
mvn -B -Pwith-ui clean verify "$@"
python3 - <<'CHECK_JAR'
from zipfile import ZipFile
with ZipFile('commerce-app/target/commerce-app-0.1.0-SNAPSHOT.jar') as jar:
    assert 'BOOT-INF/classes/static/index.html' in jar.namelist(), 'UI was not packaged'
CHECK_JAR
echo 'Verified application jar contains both UI and APIs.'
