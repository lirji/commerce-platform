#!/usr/bin/env python3
"""真实P2跨仓验收。只新增专属MySQL库和测试身份，绝不覆盖现有业务库。"""
import argparse, base64, hashlib, importlib.util, json, os, secrets, socket, subprocess, time, uuid
from pathlib import Path
from http import HTTPStatus


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--auth-run', required=True)
    parser.add_argument('--auth-root', default='../auth-platform')
    args = parser.parse_args()
    auth = Path(args.auth_root).resolve()
    fixture_dir = Path(args.auth_run).resolve()
    spec = importlib.util.spec_from_file_location('access_smoke', auth/'deploy/governance-access-smoke.py')
    upstream = importlib.util.module_from_spec(spec); spec.loader.exec_module(upstream)
    h = upstream.h
    def expect(name, port, path, headers=(), body=None, status=200, code=None):
        actual, result = h.request(port, path, headers, body)
        if actual != status or (code and result.get('code') != code):
            raise RuntimeError('HTTP assertion failed: '+name+' status='+str(actual))
        if code:
            assert set(result) == {'code','message','traceId'}
            uuid.UUID(result['traceId'])
        h.CHECKS.append({'check':name,'result':'PASS'})
        return result
    fixture = json.loads(h.read_private(fixture_dir/'fixture.json'))
    run = Path('.local/oa-auth-p2')/('smoke-'+secrets.token_hex(6)); run.mkdir(mode=0o700)
    sql_command = ['docker','exec','-i','dev-infra-mysql84-1','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names']
    def sql(statement):
        result = subprocess.run(sql_command,input=statement,text=True,capture_output=True,timeout=30)
        if result.returncode: raise RuntimeError('isolated MySQL operation failed; no credentials printed')
    suffix = secrets.token_hex(6); database = 'commerce_iam_p2_'+suffix; user = 'iam_p2_'+suffix; password = secrets.token_hex(24)
    sql(f"CREATE DATABASE {database} CHARACTER SET utf8mb4 COLLATE utf8mb4_bin; CREATE USER '{user}'@'%' IDENTIFIED BY '{password}'; GRANT ALL ON {database}.* TO '{user}'@'%';")
    env = dict(os.environ,COMMERCE_DB_URL=f'jdbc:mysql://127.0.0.1:43306/{database}?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true',
               COMMERCE_DB_USER=user,COMMERCE_DB_PASSWORD=password,COMMERCE_ADDRESS_KEY=base64.b64encode(secrets.token_bytes(32)).decode(),
               COMMERCE_PORT='18602',COMMERCE_WORKERS_ENABLED='false',COMMERCE_SANDBOX_ENABLED='false')
    h.private(run/'environment.json',json.dumps({k:v for k,v in env.items() if k.startswith('COMMERCE_')}))
    processes = []
    try:
        for kind, port in [('admin',18102),('server',18101)]:
            jar = next((auth/('auth-platform-'+kind)/'target').glob('auth-platform-'+kind+'-*.jar'))
            h.start(jar,port,run/(kind+'.log'),config=fixture_dir/(kind+'.properties'),access=True)
        with socket.socket() as guard: guard.bind(('127.0.0.1',18602))
        with os.fdopen(os.open(run/'commerce.log',os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w') as log:
            process = subprocess.Popen(['java','-jar','commerce-app/target/commerce-app-0.1.0-SNAPSHOT.jar',
                '--commerce.iam.store-read.enabled=true','--commerce.iam.store-read.configuration='+str(fixture_dir/'consumer.properties')],env=env,stdout=log,stderr=subprocess.STDOUT)
        processes.append(process)
        for _ in range(90):
            if process.poll() is not None: raise RuntimeError('commerce startup failed; see private log')
            try:
                status, _ = h.request(18602,'/actuator/health',[],None)
                if status == HTTPStatus.OK: break
            except OSError: pass
            time.sleep(1)
        else: raise RuntimeError('commerce readiness timeout')
        tenant = 'p2-'+suffix; admin_token = secrets.token_urlsafe(48); operator_token = secrets.token_urlsafe(48)
        identity_fixture = json.loads(h.read_private(auth/'.local/governance/p2/identity/casdoor.json'))
        principal = str(uuid.uuid5(uuid.NAMESPACE_URL,'p2:'+identity_fixture['users']['external']['id']))
        sql(f"USE {database}; INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES ('{hashlib.sha256(admin_token.encode()).hexdigest()}','{tenant}','admin','ADMIN',UTC_TIMESTAMP()+INTERVAL 1 HOUR),('{hashlib.sha256(operator_token.encode()).hexdigest()}','{tenant}','operator','OPERATOR',UTC_TIMESTAMP()+INTERVAL 1 HOUR); INSERT INTO merchant_record(tenant_id,merchant_id,name) VALUES ('{tenant}','m1','P2真实商家'),('foreign-{suffix}','m1','外租户商家'); INSERT INTO store_record(tenant_id,store_id,merchant_id,name) VALUES ('{tenant}','s1','m1','P2真实门店一'),('{tenant}','s2','m1','P2真实门店二'),('foreign-{suffix}','foreign','m1','不可见门店');")
        headers=[('Authorization','Bearer '+fixture['member_business_token']),('X-Tenant-Id',fixture['tenant'])]
        endpoint='/v1/operations/stores'
        expect('central allow still requires local mapping',18602,endpoint,headers,status=403,code='FORBIDDEN')
        sql(f"USE {database}; INSERT INTO central_store_identity_binding(auth_tenant_id,principal_id,membership_id,generation,tenant_id,actor_id,created_by) VALUES ('{fixture['tenant']}','{principal}','{fixture['members']['external']}',1,'{tenant}','operator','p2-isolated-smoke');")
        body=expect('real user SQL graph SDK business read',18602,endpoint,headers)
        assert {item['storeId'] for item in body}=={'s1','s2'}, 'tenant filter missing'
        assert len(expect('stable bounded pagination',18602,endpoint+'?after=s1&limit=1',headers))==1
        expect('missing credentials denied',18602,endpoint,status=401,code='UNAUTHENTICATED')
        expect('legacy admin cannot bypass central route',18602,endpoint,[('Authorization','Bearer '+admin_token),('X-Tenant-Id',fixture['tenant'])],status=401,code='UNAUTHENTICATED')
        expect('business token has no legacy admin rights',18602,'/v1/admin/members',headers,status=401,code='UNAUTHENTICATED')
        expect('legacy admin route retained',18602,'/v1/admin/members',[('Authorization','Bearer '+admin_token)])
        sql(f"USE {database}; UPDATE central_store_identity_binding SET enabled=FALSE WHERE tenant_id='{tenant}';")
        expect('local disabled binding denies immediately',18602,endpoint,headers,status=403,code='FORBIDDEN')
        sql(f"USE {database}; UPDATE central_store_identity_binding SET enabled=TRUE,generation=2 WHERE tenant_id='{tenant}';")
        expect('old generation cannot reuse new binding',18602,endpoint,headers,status=403,code='FORBIDDEN')
        sql(f"USE {database}; UPDATE central_store_identity_binding SET generation=1 WHERE tenant_id='{tenant}';")
        revoke={ 'tenant_id':fixture['tenant'],'application_id':fixture['application'],'environment':fixture['environment'],
                 'command_id':str(uuid.uuid4()),'grant_id':fixture['grant_id'],'expected_version':1 }
        expect('central revoke accepted',18102,'/api/governance/v1/access/revoke',[('Authorization','Bearer '+fixture['admin_token'])],json.dumps(revoke).encode())
        expect('pending revoke never allows business read',18602,endpoint,headers,status=503,code='UNAVAILABLE')
        jar=next((auth/'auth-platform-admin/target').glob('auth-platform-admin-*.jar'))
        result=subprocess.run(['java','-Dloader.main=com.lrj.authz.admin.governance.ProjectionCli','-cp',str(jar),'org.springframework.boot.loader.launch.PropertiesLauncher',str(fixture_dir/'projection.properties')],capture_output=True,timeout=30)
        if result.returncode: raise RuntimeError('projection failed')
        expect('revoke denies real business read',18602,endpoint,headers,status=403,code='FORBIDDEN')
        h.stop(h.PROCESSES[-1])
        expect('central outage fails closed',18602,endpoint,headers,status=503,code='UNAVAILABLE')
        (run/'result.json').write_text(json.dumps(h.CHECKS,indent=2)+'\n')
        print(json.dumps({'result':'PASS','checks':len(h.CHECKS),'evidence':str(run/'result.json'),'database':database}))
    finally:
        for process in processes+h.PROCESSES: h.stop(process)


if __name__=='__main__': main()
