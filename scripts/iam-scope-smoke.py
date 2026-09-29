#!/usr/bin/env python3
"""P3真实跨仓验收；只使用自建MySQL库、已有隔离IdP/图及本工具持有的回环进程。"""
import argparse, base64, hashlib, shutil, http.client, importlib.util, json, math, os, secrets, socket, subprocess, time, urllib.parse, uuid
from concurrent.futures import ThreadPoolExecutor
from http import HTTPStatus
from datetime import datetime, timedelta, timezone
from pathlib import Path


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--auth-root',default='../auth-platform');parser.add_argument('--p5-ui',action='store_true');parser.add_argument('--p5-only',action='store_true');args=parser.parse_args()
    auth=Path(args.auth_root).resolve();spec=importlib.util.spec_from_file_location('access',auth/'deploy/governance-access-smoke.py');up=importlib.util.module_from_spec(spec);spec.loader.exec_module(up);h=up.h
    root=auth/'.local/governance';run=Path('.local/oa-auth-p3')/('http-'+secrets.token_hex(6));run.mkdir(parents=True,mode=0o700);run=run.resolve()
    fixture=json.loads(h.read_private(root/'p2/identity/casdoor.json'));ops=json.loads(h.read_private(root/'casdoor-isolated/management-client.json'))
    dbfile=root/'database.properties'
    if args.p5_ui:
        subprocess.run(['python3',str(auth/'deploy/governance-test-db.py'),'--container','auth-governance-p4-postgres-1','--port','15434','--directory',str(run/'database')],check=True,stdout=subprocess.DEVNULL)
        dbfile=run/'database/database.properties'
    db=h.read_private(dbfile)
    if '/auth_gov_p1_test_' not in db:raise RuntimeError('only isolated governance database allowed')
    graph=h.read_private(root/'p3/graph/graph.properties');legacy=h.read_private(root/'p2/graph/graph.properties')
    if '18544' not in graph:raise RuntimeError('P3 isolated graph required')
    jars={kind:next((auth/('auth-platform-'+kind)/'target').glob('auth-platform-'+kind+'-*.jar')) for kind in ('admin','server')}
    processes=[];checks=[];convergence=[]
    commerce_jar=run/'commerce.jar';shutil.copy2(Path('commerce-app/target/commerce-app-0.1.0-SNAPSHOT.jar'),commerce_jar)
    def uid():return str(uuid.uuid4())
    def record(name):
        checks.append({'check':name,'result':'PASS'});(run/'checkpoint.json').write_text(json.dumps({'state':'IN_PROGRESS','http_checks':checks},ensure_ascii=False,indent=2)+'\n')
    def request(port,path,headers=(),body=None):
        conn=http.client.HTTPConnection('127.0.0.1',port,timeout=40)
        try:
            data=None if body is None else json.dumps(body).encode();all_headers=dict(headers)
            if data is not None:all_headers['Content-Type']='application/json'
            conn.request('GET' if data is None else 'POST',path,body=data,headers=all_headers);r=conn.getresponse();raw=r.read(4*1024*1024+1)
            if len(raw)>4*1024*1024:raise RuntimeError('bounded response exceeded')
            return r.status,json.loads(raw) if raw else {}
        finally:conn.close()
    def expect(name,port,path,headers=(),body=None,status=200,code=None):
        actual,result=request(port,path,headers,body)
        if actual!=status or (code and result.get('code')!=code):raise RuntimeError(name+': expected '+str(status)+' got '+str(actual)+' code='+str(result.get('code') if isinstance(result,dict) else 'array'))
        record(name);return result
    def cli(name,arguments):
        package='com.lrj.authz.admin.governance.' if name=='ReliableProjectionCli' else 'com.lrj.authz.governance.cli.'
        with os.fdopen(os.open(run/(name+'-'+secrets.token_hex(4)+'.log'),os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w') as out:
            result=subprocess.run(['java','-Dloader.main='+package+name,'-cp',str(jars['admin']),'org.springframework.boot.loader.launch.PropertiesLauncher',*map(str,arguments)],stdout=out,stderr=subprocess.STDOUT,timeout=40)
        if result.returncode:raise RuntimeError('controlled CLI failed: '+name+' exit='+str(result.returncode))
    tenant=uid();env='p3-'+secrets.token_hex(4);partition={'tenant_id':tenant,'application_id':'commerce','environment':env};members={};principals={}
    for kind in ('internal','external'):
        principal=str(uuid.uuid5(uuid.NAMESPACE_URL,'p2:'+fixture['users'][kind]['id']));member=uid();members[kind]=member;principals[kind]=principal
        values={'command.id':uid(),'operator.ref':'p3-fixture','tenant.id':tenant,'tenant.code':'p3-'+tenant,'principal.id':principal,'issuer':h.ISSUER,'subject':fixture['users'][kind]['id'],'membership.id':member,'valid.from':'2020-01-01T00:00:00Z','source.system':'p3-fixture','source.tenant.ref':tenant,'source.subject.ref':kind}
        file=run/(kind+'.properties');h.private(file,h.props(values));cli('GovernanceCli',['bootstrap',dbfile,file])
    catalog={'catalog.application':'commerce','catalog.owner-principal':principals['internal'],'catalog.entry-origin':'http://127.0.0.1:8601','catalog.operator':'p3-fixture','catalog.command':uid(),'catalog.owner-issuer':h.ISSUER,'catalog.owner-subject':fixture['users']['internal']['id']}
    h.private(run/'catalog.properties',db+h.props(catalog));cli('CatalogCli',['register',run/'catalog.properties','configured'])
    manifest={'schema_version':'1','application':'commerce','manifest_version':2,'capabilities':[{'code':'commerce.store.read','resource_type':'store','risk_level':'NORMAL'},{'code':'commerce.store.manage','resource_type':'store','risk_level':'HIGH'},{'code':'commerce.product.read','resource_type':'product','risk_level':'NORMAL'}],
        'menus':[{'code':'stores','parent':None,'route':'/stores','any_of':['commerce.store.read']},{'code':'products','parent':None,'route':'/products','any_of':['commerce.product.read']}]}
    if args.p5_ui:
        manifest['capabilities'].extend([{'code':'commerce.product.update','resource_type':'product','risk_level':'HIGH'},{'code':'commerce.product.export','resource_type':'product','risk_level':'HIGH'}])
        manifest['menus'][1]['route']='/operations/products'
    (run/'manifest.json').write_text(json.dumps(manifest));cli('CatalogCli',['publish',run/'catalog.properties',run/'manifest.json'])
    access={'access.tenant':tenant,'access.application':'commerce','access.environment':env,'access.manager':members['internal'],'access.generation':1,'access.capabilities':'commerce.store.read,commerce.store.manage,commerce.product.read','access.max-duration-seconds':3600,'access.operator':'p3-fixture','access.command':uid()}
    if args.p5_ui:access['access.capabilities']+=',commerce.product.update'
    h.private(run/'access.properties',db+h.props(access));cli('AccessBootstrapCli',[run/'access.properties'])
    def authority(purpose):
        c=fixture['clients'][purpose];return {'issuer':h.ISSUER,'jwks.uri':h.ISSUER+'/.well-known/jwks','audience':c['name'],'client.id':c['name'],'client.secret':c['secret'],'version-probe.client.id':ops['client_id'],'version-probe.client.secret':ops['client_secret']}
    p3graph=''.join('scope.'+line+'\n' for line in graph.splitlines() if line.startswith('graph.'))
    h.private(run/'admin.properties',db+legacy+p3graph+h.props(authority('management')))
    service=secrets.token_urlsafe(48);server={'service.count':1,'service.1.id':'commerce-p3','service.1.application-id':'commerce','service.1.environment':env,'service.1.operation':'context.resolve','service.1.credential-sha256':hashlib.sha256(service.encode()).hexdigest(),'access.check.callers':'commerce-p3','scope.check.callers':'commerce-p3','scope.owner.commerce':'store,product'}
    server.update({'service.1.user.'+k:v for k,v in authority('business').items()});h.private(run/'server.properties',db+legacy+p3graph+h.props(server))
    h.private(run/'consumer.properties',h.props({'central.url':'http://127.0.0.1:18111','central.credential':service,'central.application':'commerce','central.environment':env}))
    admin_token=up.token(h.ISSUER,fixture,'management','internal');user_token=up.token(h.ISSUER,fixture,'business','external');menu_token=up.token(h.ISSUER,fixture,'management','external')
    admin=[('Authorization','Bearer '+admin_token)];user=[('Authorization','Bearer '+user_token),('X-Tenant-Id',tenant)];dual=[('Authorization','Bearer '+service),('X-User-Access-Token',user_token)]
    h.private(run/'fixture.json',json.dumps({'tenant':tenant,'environment':env,'members':members,'principals':principals,'admin_token':admin_token,'user_token':user_token,'menu_token':menu_token}))
    sql_command=['docker','exec','-i','dev-infra-mysql84-1','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names']
    def sql(statement):
        result=subprocess.run(sql_command,input=statement,text=True,capture_output=True,timeout=30)
        if result.returncode:raise RuntimeError('owned MySQL command failed; see SQL contract, no credentials printed')
        return result.stdout
    suffix=secrets.token_hex(6);database='commerce_iam_p3_'+suffix;dbuser='iam_p3_'+suffix;password=secrets.token_hex(24)
    sql(f"CREATE DATABASE {database} CHARACTER SET utf8mb4 COLLATE utf8mb4_bin; CREATE USER '{dbuser}'@'%' IDENTIFIED BY '{password}'; GRANT ALL ON {database}.* TO '{dbuser}'@'%';")
    local='p3-'+suffix;local_admin=secrets.token_urlsafe(48)
    environment=dict(os.environ,COMMERCE_DB_URL=f'jdbc:mysql://127.0.0.1:43306/{database}?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true',COMMERCE_DB_USER=dbuser,COMMERCE_DB_PASSWORD=password,COMMERCE_ADDRESS_KEY=base64.b64encode(secrets.token_bytes(32)).decode(),COMMERCE_PORT='18603',COMMERCE_WORKERS_ENABLED='false',COMMERCE_SANDBOX_ENABLED='false')
    h.private(run/'environment.json',json.dumps({k:v for k,v in environment.items() if k.startswith('COMMERCE_')}))
    def start_commerce(label):
        with socket.socket() as guard:guard.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);guard.bind(('127.0.0.1',18603))
        with os.fdopen(os.open(run/(label+'.log'),os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w') as log:
            process=subprocess.Popen(['java','-Xmx512m','-jar',str(commerce_jar),'--server.address=127.0.0.1','--commerce.iam.store-read.enabled=true','--commerce.iam.scope.enabled=true','--commerce.iam.store-read.configuration='+str(run/'consumer.properties')],env=environment,stdout=log,stderr=subprocess.STDOUT)
        processes.append(process)
        for _ in range(90):
            if process.poll() is not None:raise RuntimeError('owned commerce startup failed; private log '+label)
            try:
                if request(18603,'/actuator/health')[0]==200:return process
            except OSError:pass
            time.sleep(.5)
        raise RuntimeError('owned commerce readiness timeout')
    def projection():
        for kind in ('POLICY','DIRECTORY'):
            file=run/('projection-'+kind+'-'+secrets.token_hex(4)+'.properties');h.private(file,db+graph+h.props({**access,'projection.kind':kind}));cli('ReliableProjectionCli',[file])
    prefix='/api/governance/v1/access';base='/v1/operations/scoped/';endpoint='/internal/governance/v1/access/scope-plan'
    def check(resource='store'):return {'tenant_id':tenant,'expected_membership_generation':1,'request_id':uid(),'capability':'commerce.'+resource+'.read','resource_type':resource}
    def role(resource):return expect('create '+resource+' role',18112,prefix+'/roles',admin,{**partition,'command_id':uid(),'role_code':resource+'-reader','role_version':1,'capabilities':['commerce.'+resource+'.read']})['id']
    def grant(role_id,resource,clauses,source):
        now=datetime.now(timezone.utc);return expect('grant '+source,18112,prefix+'/scoped-grants',admin,{**partition,'command_id':uid(),'member_id':members['external'],'member_generation':1,'role_id':role_id,'scope_rule':{'version':1,'resource_type':resource,'clauses':clauses},'source_id':source,'valid_from':(now-timedelta(seconds=2)).isoformat().replace('+00:00','Z'),'valid_to':(now+timedelta(minutes=50)).isoformat().replace('+00:00','Z')},202)
    def clause(kind,values):return {'kind':kind,'values':values,'include_root':False}
    def converge():
        started=time.monotonic();deadline=started+15
        while time.monotonic()<deadline:
            replies=[request(port,endpoint,dual,check())[1] for port in (18111,18113)]
            if all(r.get('decision')=='ALLOW' for r in replies):convergence.append(round((time.monotonic()-started)*1000,3));return
            time.sleep(.2)
        raise RuntimeError('strict graph convergence deadline exceeded')
    try:
        h.start(jars['admin'],18112,run/'admin.log',config=run/'admin.properties',access=True,presentation=True,scope=True)
        for port in (18111,18113):h.start(jars['server'],port,run/('server-'+str(port)+'.log'),config=run/'server.properties',access=True,scope=True)
        expect('explicit strict cutover',18112,prefix+'/enable-strict',admin,{**partition,'command_id':uid(),'kind':None},202)
        roles={kind:role(kind) for kind in ('store','product')}
        stores=grant(roles['store'],'store',[clause('SPECIFIED_STORES',['S001','S002'])],'stores-two')
        products=grant(roles['product'],'product',[clause('SPECIFIED_STORES',['S001']),clause('SPECIFIED_RESOURCES',['P001','P002'])],'products-and')
        expect('strict old P2 endpoint refuses downgrade',18111,'/internal/governance/v1/access/check',dual,check(),503,'AUTHZ_STATE_NOT_READY')
        projection();converge();commerce=start_commerce('commerce')
        sql(f"USE {database}; INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES ('{hashlib.sha256(local_admin.encode()).hexdigest()}','{local}','admin','ADMIN',UTC_TIMESTAMP()+INTERVAL 1 HOUR),('{hashlib.sha256(secrets.token_bytes(48)).hexdigest()}','{local}','operator','OPERATOR',UTC_TIMESTAMP()+INTERVAL 1 HOUR); INSERT INTO merchant_record(tenant_id,merchant_id,name) VALUES ('{local}','M','P3商家'),('foreign-{suffix}','M','外企业');")
        rows=','.join(f"('{local}','S{i:03d}','M','store-{i}')" for i in range(1,56));sql(f"USE {database}; INSERT INTO store_record(tenant_id,store_id,merchant_id,name) VALUES {rows},('foreign-{suffix}','FOREIGN','M','foreign-secret'); INSERT INTO catalog_product(tenant_id,product_id,store_id,title,category,brand) VALUES ('{local}','P001','S001','visible-product','C','B'),('{local}','P002','S002','hidden-product','C','B'),('{local}','P003','S001','hidden-product','C','B'),('foreign-{suffix}','FOREIGN','FOREIGN','foreign-secret','C','B');")
        expect('central plan still requires local bridge',18603,base+'store',user,status=403,code='FORBIDDEN')
        sql(f"USE {database}; INSERT INTO central_store_identity_binding(auth_tenant_id,principal_id,membership_id,generation,tenant_id,actor_id,created_by) VALUES ('{tenant}','{principals['external']}','{members['external']}',1,'{local}','operator','p3-http');")
        page=expect('store SQL range and total',18603,base+'store?limit=1',user);assert page['total']==2 and page['stores']==2 and page['items'][0]['resourceId']=='S001';cursor=page['nextCursor']
        second=expect('bound cursor returns only second store',18603,base+'store?limit=1&cursor='+cursor,user);assert second['items'][0]['resourceId']=='S002' and second['nextCursor'] is None
        product=expect('product AND scope before SQL count',18603,base+'product',user);assert product['total']==1 and [r['resourceId'] for r in product['items']]==['P001']
        hidden=expect('search cannot expose hidden totals',18603,base+'product?search=hidden',user);assert hidden['total']==0 and hidden['stores']==0
        expect('trusted product details allowed',18603,base+'product/resources/P001',user)
        expect('same store does not bypass explicit product constraint',18603,base+'product/resources/P003',user,status=403,code='FORBIDDEN')
        expect('wrong store blocks explicit product id',18603,base+'product/resources/P002',user,status=403,code='FORBIDDEN')
        expect('foreign resource id does not leak',18603,base+'store/resources/FOREIGN',user,status=403,code='FORBIDDEN')
        legacy=expect('P2 store route now uses scoped SQL',18603,'/v1/operations/stores',user);assert {r['storeId'] for r in legacy}=={'S001','S002'}
        expect('raw old resource cursor rejected',18603,'/v1/operations/stores?after=S001',user,status=403,code='FORBIDDEN')
        expect('legacy admin cannot enter scoped route',18603,base+'store',[('Authorization','Bearer '+local_admin),('X-Tenant-Id',tenant)],status=401,code='UNAUTHENTICATED')
        expect('user token cannot enter legacy admin',18603,'/v1/admin/members',user,status=401,code='UNAUTHENTICATED')
        if args.p5_ui:
            # external只是隔离IdP账号名称；本场景经bootstrap建立的是EMPLOYEE，非合作成员。
            ui_env=dict(os.environ,VITE_IAM_ENABLED='true',VITE_IAM_AUTHORITY=h.ISSUER,VITE_IAM_CLIENT_ID=fixture['clients']['business']['name'],COMMERCE_API_URL='http://127.0.0.1:18603')
            h.private(run/'pilot.json',json.dumps({'tenant':tenant,'token':user_token,'authority':h.ISSUER,'client':fixture['clients']['business']['name']}))
            with open(run/'pilot-vite.log','w') as log:
                vite=subprocess.Popen(['npm','run','dev','--','--port','18605'],cwd='frontend',env=ui_env,stdout=log,stderr=subprocess.STDOUT)
            processes.append(vite)
            for _ in range(40):
                if vite.poll() is not None:raise RuntimeError('pilot Vite failed')
                try:
                    with socket.create_connection(('127.0.0.1',18605),timeout=1):break
                except OSError:time.sleep(.25)
            def browser(phase):
                subprocess.run(['node','scripts/iam-pilot-browser.mjs'],env=dict(ui_env,P5_RUN=str(run),P5_PHASE=phase),check=True,timeout=120)
                record('real internal browser '+phase)
            browser('read-only')
            writer=expect('register actual product update role',18112,prefix+'/roles',admin,{**partition,'command_id':uid(),'role_code':'product-editor','role_version':1,'capabilities':['commerce.product.update']})['id']
            writing=grant(writer,'product',[clause('SPECIFIED_STORES',['S001'])],'product-write');projection()
            deadline=time.monotonic()+15
            while time.monotonic()<deadline:
                action_status,action=request(18603,base+'product/resources/P001/actions',user)
                if action_status==HTTPStatus.OK and action.get('update'):break
                time.sleep(.25)
            else:raise RuntimeError('actual product action did not converge: '+str(action_status)+' '+str(action))
            browser('write')
            expect('revoke product write source',18112,prefix+'/strict-revoke',admin,{**partition,'command_id':uid(),'grant_id':writing['id'],'expected_version':1},202)
            projection();browser('revoked')
            assert sql(f"USE {database}; SELECT title FROM catalog_product WHERE tenant_id='{local}' AND product_id='P001';").strip()=='P5 browser edited product'
            if args.p5_only:
                (run/'result.json').write_text(json.dumps({'result':'PASS','http_checks':checks},indent=2));print('PASS P5 internal '+str(run));return
        menu=expect('strict menu hints use real grants',18112,'/api/governance/v1/me/access?'+urllib.parse.urlencode(partition),[('Authorization','Bearer '+menu_token)]);assert set(menu['capability_hints'])=={'commerce.store.read','commerce.product.read'}
        forged={**check(),'principal_id':uid()};expect('browser identity injection rejected',18111,endpoint,dual,forged,400,'INVALID_ARGUMENT')
        expect('wrong service credential rejected',18113,endpoint,[('Authorization','Bearer '+'x'*48),('X-User-Access-Token',user_token)],check(),401,'INVALID_CREDENTIAL')
        expect('wrong user audience rejected',18113,endpoint,[('Authorization','Bearer '+service),('X-User-Access-Token',admin_token)],check(),401,'INVALID_CREDENTIAL')
        facts={'tenant_id':uid(),'resource_type':'store','resource_id':'S001','resource_version':0,'owner_principal_id':None,'department_id':None,'department_ancestors':[],'store_id':'S001','supplier_id':None}
        expect('resource Owner cannot substitute tenant',18111,'/internal/governance/v1/access/check-resource',dual,{'check':check(),'facts':facts},400,'INVALID_ARGUMENT')
        def jobcall(name,path,version=None,key=None):return expect(name,18603,base+'store/exports'+path+('' if version is None else '?version='+str(version)),user+[('Idempotency-Key',key or uid())],{},202 if path=='' else 200)
        queued=jobcall('submit export while currently allowed','')
        revoke={**partition,'command_id':uid(),'grant_id':stores['id'],'expected_version':1}
        accepted=expect('strict revoke is accepted not completed',18112,prefix+'/strict-revoke',admin,revoke,202);assert accepted['status']=='PROCESSING'
        expect('second auth node blocks old graph immediately',18113,endpoint,dual,check(),503,'AUTHZ_STATE_NOT_READY')
        expect('queued export cannot start during revoke',18603,base+'store/exports/'+queued['id']+'/start?version=1',user+[('Idempotency-Key',uid())],{},503,'UNAVAILABLE')
        projection();receipt=expect('query real completed revocation receipt',18112,prefix+'/revocation-receipt?'+urllib.parse.urlencode({**partition,'grant_id':stores['id']}),admin);assert receipt['status']=='COMPLETED' and receipt['operation_id']
        for port in (18111,18113):assert expect('new request denies on node '+str(port),port,endpoint,dual,check())['decision']=='DENY'
        expect('business read denied after receipt',18603,base+'store',user,status=403,code='FORBIDDEN')
        broad=grant(roles['store'],'store',[clause('TENANT_ALL',[])],'export-all');projection();converge()
        expect('old cursor invalid after new policy',18603,base+'store?cursor='+cursor,user,status=403,code='FORBIDDEN')
        expect('queued old task cannot adopt new policy',18603,base+'store/exports/'+queued['id']+'/start?version=1',user+[('Idempotency-Key',uid())],{},403,'FORBIDDEN')
        job=jobcall('submit fresh bounded export','');started=jobcall('start with current scope','/'+job['id']+'/start',job['version']);key=uid();batch=jobcall('first batch commits fifty rows','/'+job['id']+'/advance',started['version'],key);assert batch['rowCount']==50 and batch['state']=='RUNNING'
        assert jobcall('same batch command replay does not duplicate','/'+job['id']+'/advance',started['version'],key)==batch
        old_pid=commerce.pid;commerce.kill();commerce.wait(timeout=10);commerce=start_commerce('commerce-recovered');assert commerce.pid!=old_pid
        complete=jobcall('new process resumes persisted checkpoint','/'+job['id']+'/advance',batch['version']);assert complete['state']=='COMPLETED' and complete['rowCount']==55
        download=expect('download rechecks all authorized rows',18603,base+'store/exports/'+job['id']+'/download',user);assert len(download['rows'])==55 and all(r['resourceId']!='FOREIGN' for r in download['rows'])
        record('real commerce process kill recovery old='+str(old_pid)+' new='+str(commerce.pid))
        # 固定并发和样本数仅提供本机基线，不声明生产SLO或容量。
        def timed(index):
            started=time.perf_counter();port=(18111,18113)[index%2];status,result=request(port,endpoint,dual,check());elapsed=(time.perf_counter()-started)*1000
            if status!=HTTPStatus.OK or result.get('decision')!='ALLOW':raise RuntimeError('baseline authorization failed')
            return elapsed
        with ThreadPoolExecutor(max_workers=2) as pool:auth_latency=list(pool.map(timed,range(60)))
        business_latency=[]
        for _ in range(30):
            started=time.perf_counter();status,result=request(18603,base+'store?limit=20',user)
            if status!=HTTPStatus.OK or result['total']!=55:raise RuntimeError('business baseline failed')
            business_latency.append((time.perf_counter()-started)*1000)
        def stats(values):
            ordered=sorted(values);return {'samples':len(values),'p50_ms':round(ordered[math.ceil(len(values)*.50)-1],3),'p95_ms':round(ordered[math.ceil(len(values)*.95)-1],3),'p99_ms':round(ordered[math.ceil(len(values)*.99)-1],3),'max_ms':round(max(values),3)}
        performance={'scope_plan_two_nodes_concurrency_2':stats(auth_latency),'commerce_scope_page_concurrency_1':stats(business_latency),'new_grant_convergence_ms':convergence,'dataset':{'stores':55,'products':3,'foreign_tenant_rows':2},'scope':'isolated local functional baseline; not production capacity'}
        (run/'performance.json').write_text(json.dumps(performance,indent=2)+'\n');record('fixed sample dual-node and business latency baseline')
        # 下载对象已经生成仍需实时检查；SQL受理后马上阻断，不能依赖旧导出文件。
        expect('revoke broad source',18112,prefix+'/strict-revoke',admin,{**partition,'command_id':uid(),'grant_id':broad['id'],'expected_version':1},202)
        expect('completed download denied while projection pending',18603,base+'store/exports/'+job['id']+'/download',user,status=503,code='UNAVAILABLE');projection()
        expect('completed download denied after revocation',18603,base+'store/exports/'+job['id']+'/download',user,status=403,code='FORBIDDEN')
        # 另一个资源来源仍有效，证明撤一个来源没有误撤独立路径。
        assert expect('product source remains independent',18603,base+'product',user)['total']==1
        h.stop(h.PROCESSES[1]);expect('auth outage does not fall back to legacy ACL',18603,base+'product',user,status=503,code='UNAVAILABLE')
        (run/'result.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2)+'\n')
        print(json.dumps({'result':'PASS','checks':len(checks),'evidence':str(run/'result.json'),'performance':str(run/'performance.json'),'database':database}))
    finally:
        for process in processes+h.PROCESSES:h.stop(process)

if __name__=='__main__':main()
