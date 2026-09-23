#!/usr/bin/env python3
"""将既有本地私密配置映射为容器配置，保留密钥，不创建共享组件。"""
from pathlib import Path
import os,shlex
root=Path(__file__).resolve().parents[1];target=root/'.local/compose.env'
if target.exists():
    print('Existing private Compose configuration retained.');raise SystemExit(0)
values={}
for line in (root/'.local/runtime.env').read_text().splitlines():
    key,value=line.removeprefix('export ').split('=',1);values[key]=shlex.split(value)[0]
if '/commerce_local?' not in values['COMMERCE_DB_URL']:raise SystemExit('Only the dedicated local project schema can be configured.')
settings={key:values[key] for key in ['COMMERCE_DB_USER','COMMERCE_DB_PASSWORD','COMMERCE_ADDRESS_KEY','COMMERCE_SANDBOX_ENABLED','COMMERCE_WORKERS_ENABLED']}
settings.update(COMMERCE_DOCKER_DB_URL='jdbc:mysql://mysql84:3306/commerce_local?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true',COMMERCE_HTTP_PORT='8602',COMMERCE_INFRA_NETWORK='dev-infra',COMMERCE_IMAGE_TAG='local')
fd=os.open(target,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
with os.fdopen(fd,'w') as f:
    for key,value in settings.items():
        if "'" in value or '\n' in value:raise SystemExit('Unsupported local env value; configure it through a controlled credential mechanism.')
        f.write(key+"='"+value+"'\n")
print('Private Compose configuration written; no credential values printed.')
