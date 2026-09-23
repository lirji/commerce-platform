#!/usr/bin/env python3
"""仅在GitHub临时runner创建独占MySQL；随机口令不复用平台令牌或输出日志。"""
from pathlib import Path
import base64,os,secrets,subprocess,time
if os.getenv('GITHUB_ACTIONS')!='true':raise SystemExit('This bootstrap is restricted to the ephemeral CI runner.')
# 只创建本次运行自己的容器，不接受调用方传入共享实例作为初始化目标。
run_id=os.environ['GITHUB_RUN_ID'];attempt=os.environ['GITHUB_RUN_ATTEMPT']
if not run_id.isdigit() or not attempt.isdigit():raise SystemExit('Invalid isolated CI identity.')
container='commerce-ci-mysql-'+run_id+'-'+attempt
password=secrets.token_hex(32);root_password=secrets.token_hex(32);key=base64.b64encode(secrets.token_bytes(32)).decode()
Path('.local').mkdir(mode=0o700,exist_ok=True)
# 先以排他方式取得配置路径，重复启动不能覆写已有凭据或重建数据。
fd=os.open('.local/runtime.env',os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
with open(os.environ['GITHUB_ENV'],'a') as f:f.write('COMMERCE_MYSQL_CONTAINER='+container+'\n')
created=subprocess.run(['docker','run','--detach','--name',container,'--publish','127.0.0.1:3306:3306','--env','MYSQL_ROOT_PASSWORD','mysql:8.4.11'],env=dict(os.environ,MYSQL_ROOT_PASSWORD=root_password),capture_output=True,text=True,timeout=180)
if created.returncode:os.close(fd);raise SystemExit('Ephemeral CI MySQL could not be created; private diagnostics suppressed.')
process_env=dict(os.environ,MYSQL_PWD=root_password)
command=['docker','exec','-i','-e','MYSQL_PWD',container,'mysql','--protocol=TCP','-h127.0.0.1','-uroot']
# TCP确保入口脚本的临时socket服务器已经退出，真实口令也已经生效。
for _ in range(60):
 ready=subprocess.run(command,input='SELECT 1;',text=True,capture_output=True,env=process_env,timeout=10)
 if ready.returncode==0:break
 time.sleep(2)
else:os.close(fd);raise SystemExit('Ephemeral CI MySQL did not become ready.')
sql=f"""CREATE DATABASE commerce_local CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE DATABASE commerce_test_20260923 CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE USER 'commerce_app'@'%' IDENTIFIED BY '{password}';
GRANT ALL ON commerce_local.* TO 'commerce_app'@'%';
GRANT ALL ON commerce_test_20260923.* TO 'commerce_app'@'%';
"""
r=subprocess.run(command,input=sql,text=True,capture_output=True,env=process_env,timeout=30)
if r.returncode:os.close(fd);raise SystemExit('CI database bootstrap failed; sensitive diagnostic output suppressed.')
values={'COMMERCE_DB_URL':'jdbc:mysql://127.0.0.1:3306/commerce_local?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true','COMMERCE_TEST_DB_URL':'jdbc:mysql://127.0.0.1:3306/commerce_test_20260923?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true','COMMERCE_DB_USER':'commerce_app','COMMERCE_DB_PASSWORD':password,'COMMERCE_ADDRESS_KEY':key,'COMMERCE_SANDBOX_ENABLED':'true','COMMERCE_WORKERS_ENABLED':'true'}
with os.fdopen(fd,'w') as f:
 for name,value in values.items():f.write("export "+name+"='"+value+"'\n")
print('Ephemeral CI MySQL and dedicated schemas ready; private configuration written.')
