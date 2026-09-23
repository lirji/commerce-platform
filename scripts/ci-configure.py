#!/usr/bin/env python3
"""只为GitHub Actions新建的隔离MySQL服务创建验证资源，不支持共享实例。"""
from pathlib import Path
import base64,json,os,secrets,subprocess
if os.getenv('GITHUB_ACTIONS')!='true':raise SystemExit('This bootstrap is restricted to the ephemeral CI runner.')
container=os.environ['COMMERCE_MYSQL_CONTAINER']
if not container or 'dev-infra' in container:raise SystemExit('Shared infrastructure must never be used by CI bootstrap.')
password=secrets.token_hex(32);key=base64.b64encode(secrets.token_bytes(32)).decode()
sql=f"""CREATE DATABASE commerce_local CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE DATABASE commerce_test_20260923 CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE USER 'commerce_app'@'%' IDENTIFIED BY '{password}';
GRANT ALL ON commerce_local.* TO 'commerce_app'@'%';
GRANT ALL ON commerce_test_20260923.* TO 'commerce_app'@'%';
"""
r=subprocess.run(['docker','exec','-i','-e','MYSQL_PWD',container,'mysql','-uroot'],input=sql,text=True,capture_output=True,env=dict(os.environ,MYSQL_PWD=os.environ['MYSQL_ROOT_PASSWORD']),timeout=30)
if r.returncode:raise SystemExit('CI database bootstrap failed; sensitive diagnostic output suppressed.')
Path('.local').mkdir(mode=0o700,exist_ok=True)
values={'COMMERCE_DB_URL':'jdbc:mysql://127.0.0.1:3306/commerce_local?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true','COMMERCE_TEST_DB_URL':'jdbc:mysql://127.0.0.1:3306/commerce_test_20260923?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true','COMMERCE_DB_USER':'commerce_app','COMMERCE_DB_PASSWORD':password,'COMMERCE_ADDRESS_KEY':key,'COMMERCE_SANDBOX_ENABLED':'true','COMMERCE_WORKERS_ENABLED':'true'}
fd=os.open('.local/runtime.env',os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
with os.fdopen(fd,'w') as f:
    for name,value in values.items():f.write("export "+name+"='"+value+"'\n")
print('Ephemeral CI schemas and private runtime configuration created.')
