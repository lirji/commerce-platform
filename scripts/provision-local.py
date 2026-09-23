#!/usr/bin/env python3
"""为本项目创建隔离数据库和专用账号，不读取或输出其他项目数据。"""
from pathlib import Path
import base64, os, secrets, subprocess, sys
root = Path(__file__).resolve().parents[1]
env_file = root / '.local' / 'runtime.env'
if env_file.exists():
    print('Local environment already provisioned; retained existing credentials.')
    sys.exit(0)
password = secrets.token_hex(24)
query = "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME IN ('commerce_local','commerce_test_20260923'); SELECT User FROM mysql.user WHERE User='commerce_app';"
command = ['docker','exec','-i','dev-infra-mysql84-1','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names']
check = subprocess.run(command,input=query,text=True,capture_output=True,timeout=20)
if check.returncode or check.stdout.strip():
    sys.exit('Provision refused: existing resources or unavailable database; no credentials overwritten.')
sql = f"""CREATE DATABASE commerce_local CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE DATABASE commerce_test_20260923 CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE USER 'commerce_app'@'%' IDENTIFIED BY '{password}';
GRANT ALL PRIVILEGES ON commerce_local.* TO 'commerce_app'@'%';
GRANT ALL PRIVILEGES ON commerce_test_20260923.* TO 'commerce_app'@'%';
"""
result = subprocess.run(command,input=sql,text=True,capture_output=True,timeout=20)
if result.returncode:
    sys.exit('Provision failed; inspect database locally. No secrets printed and no destructive retry performed.')
env_file.parent.mkdir(exist_ok=True,mode=0o700)
fd=os.open(env_file,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
with os.fdopen(fd,'w') as f:
    f.write("export COMMERCE_DB_URL='jdbc:mysql://127.0.0.1:43306/commerce_local?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'\n")
    f.write("export COMMERCE_TEST_DB_URL='jdbc:mysql://127.0.0.1:43306/commerce_test_20260923?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'\n")
    f.write("export COMMERCE_DB_USER='commerce_app'\n")
    f.write(f"export COMMERCE_DB_PASSWORD='{password}'\n")
    f.write(f"export COMMERCE_ADDRESS_KEY='{base64.b64encode(secrets.token_bytes(32)).decode()}'\n")
print('Created project-only schemas and account; secrets saved with mode 0600 in .local/runtime.env.')
