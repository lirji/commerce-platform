#!/usr/bin/env python3
"""
第四阶段真实进程验证（测试库，端口8606，经本地TCP代理43307→43306，从不暂停共享MySQL容器）：
 A. kill -9 重启：积分过期与周期考核积压处理到一半时强杀进程，重启后核对副作用恰好一次。
 B. 有界浸泡（默认10分钟）：持续下单产生新事件、毒批次进入隔离、中途数据库断连30秒、运维恢复、
    历史重放与保留期清理同时运行；每30秒采样平台视图、JVM堆与线程、应用到数据库的连接数。
用法: live-soak.py <输出目录> [浸泡秒数]
凭据只以哈希写入测试库，不输出明文。
时间一律用UTC_TIMESTAMP：MySQL服务器会话时区不是UTC，应用连接强制UTC会话，夹具时间必须与应用一致。
"""
import hashlib, json, os, shlex, signal, subprocess, sys, time, urllib.request, uuid, datetime
ROOT='/Users/liruijun/personal/LLM/commerce-platform'
OUT=sys.argv[1]; SOAK=int(sys.argv[2]) if len(sys.argv)>2 else 600
os.makedirs(OUT,exist_ok=True)
env={}
for line in open(f'{ROOT}/.local/runtime.env'):
    if line.strip() and not line.startswith('#'):
        k,v=line.removeprefix('export ').strip().split('=',1);env[k]=shlex.split(v)[0]
SCHEMA='commerce_test_20260923'
assert '/'+SCHEMA+'?' in env['COMMERCE_TEST_DB_URL']
RUN=uuid.uuid4().hex[:8];T=f'soak-{RUN}';OLD=f'soak-old-{RUN}'
PORT=8606;BASE=f'http://127.0.0.1:{PORT}'
# 只对本进程绕过系统代理访问本机，不修改系统代理设置。
urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({})))
log=open(f'{OUT}/live-soak.txt','a')
def say(*a):
    line=' '.join(str(x) for x in a);print(line,flush=True);log.write(line+'\n');log.flush()
def sql(q,fetch=False):
    p=subprocess.run(['docker','exec','-i','-e','MYSQL_PWD','dev-infra-mysql84-1','mysql','-N','-B','-ucommerce_app',SCHEMA],input='SET SESSION cte_max_recursion_depth=100000;'+q,text=True,capture_output=True,env=dict(os.environ,MYSQL_PWD=env['COMMERCE_DB_PASSWORD']),timeout=120)
    if p.returncode:raise SystemExit('sql failed: '+p.stderr[-300:])
    return [r.split('\t') for r in p.stdout.strip().splitlines()] if fetch else None
def one(q):return sql(q,True)[0][0]
def token(tenant,actor,role):
    t=uuid.uuid4().hex+uuid.uuid4().hex
    sql(f"INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES('{hashlib.sha256(t.encode()).hexdigest()}','{tenant}','{actor}','{role}',DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 1 DAY));")
    return t
def call(method,path,tok,body=None,key=None):
    req=urllib.request.Request(BASE+path,method=method,data=None if body is None else json.dumps(body).encode(),headers={'Authorization':'Bearer '+tok,'Content-Type':'application/json',**({'Idempotency-Key':key} if key else {})})
    try:
        with urllib.request.urlopen(req,timeout=15) as r:return r.status,json.loads(r.read() or b'null')
    except urllib.error.HTTPError as e:return e.code,None
    except Exception:return -1,None
proxy=None;app=None
def start_proxy():
    global proxy;proxy=subprocess.Popen([sys.executable,f'{ROOT}/docs/evidence/phase3-background-runtime/scripts/dbproxy.py','43307','43306'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL);time.sleep(0.5)
def start_app(tag,extra=None):
    global app
    e=dict(os.environ,**env);e.update({'COMMERCE_DB_URL':env['COMMERCE_TEST_DB_URL'].replace(':43306/',':43307/'),'COMMERCE_PORT':str(PORT),'COMMERCE_WORKERS_ENABLED':'true','COMMERCE_SANDBOX_ENABLED':'true','no_proxy':'127.0.0.1,localhost'})
    if extra:e.update(extra)
    app=subprocess.Popen(['java','-jar',f'{ROOT}/commerce-app/target/commerce-app-0.1.0-SNAPSHOT.jar'],stdout=open(f'{OUT}/app-{tag}.log','w'),stderr=subprocess.STDOUT,env=e)
    for _ in range(120):
        try:
            with urllib.request.urlopen(BASE+'/actuator/health',timeout=2) as r:
                if r.status==200:return
        except Exception:time.sleep(1)
    raise SystemExit('app did not start')
def stop_app(hard):
    if app and app.poll() is None:
        app.send_signal(signal.SIGKILL if hard else signal.SIGTERM);app.wait(timeout=60)
def jvm():
    pid=app.pid
    heap=subprocess.run(['jcmd',str(pid),'GC.heap_info'],capture_output=True,text=True).stdout
    used=[l for l in heap.splitlines() if 'used' in l][:1]
    threads=subprocess.run(['jcmd',str(pid),'Thread.print'],capture_output=True,text=True).stdout.count('\n"')
    return {'heap':used[0].strip() if used else None,'threads':threads}
try:
    start_proxy()
    # ---------------- 准备数据（SQL，测试库专属租户） ----------------
    say('run',RUN,'tenant',T,'at',datetime.datetime.now(datetime.timezone.utc).isoformat())
    sql(f"INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level) WITH RECURSIVE s(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM s WHERE i<500) SELECT '{T}',CONCAT('m',LPAD(i,4,'0')),CONCAT('a',i),'浸泡会员','L1' FROM s;"
        f"INSERT INTO member_point_account(tenant_id,member_id) SELECT tenant_id,member_id FROM member_record WHERE tenant_id='{T}';"
        f"INSERT INTO member_point_lot(tenant_id,lot_id,member_id,policy_version,credited,remaining,expires_at) WITH RECURSIVE s(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM s WHERE i<2000) SELECT '{T}',CONCAT('lot',LPAD(i,5,'0')),CONCAT('m',LPAD(1+MOD(i,500),4,'0')),1,10,10,TIMESTAMPADD(SECOND,-60-i,UTC_TIMESTAMP(3)) FROM s;"
        f"INSERT INTO member_cycle_policy(tenant_id,version,effective_from,policy_json) VALUES('{T}',1,'2000-01-01 00:00:00.000','{{\"version\":1,\"effectiveFrom\":\"2000-01-01T00:00:00Z\",\"periodDays\":30,\"levels\":[{{\"code\":\"L1\",\"minimumGrowth\":0}}]}}');")
    # ---------------- A. kill -9 重启 ----------------
    start_app('a1')
    for _ in range(300):
        done=int(one(f"SELECT COUNT(*) FROM member_point_ledger WHERE tenant_id='{T}' AND action='EXPIRE'"))
        if done>=200:break
        time.sleep(0.2)
    stop_app(True)
    mid={'expired':int(one(f"SELECT COUNT(*) FROM member_point_ledger WHERE tenant_id='{T}' AND action='EXPIRE'")),'assessed':int(one(f"SELECT COUNT(*) FROM member_cycle_account WHERE tenant_id='{T}'"))}
    say('A kill -9 during backlog',json.dumps(mid))
    start_app('a2')
    for _ in range(600):
        left=int(one(f"SELECT COUNT(*) FROM member_point_lot WHERE tenant_id='{T}' AND remaining>0"));todo=int(one(f"SELECT COUNT(*) FROM member_record WHERE tenant_id='{T}' AND cycle_due_at<=UTC_TIMESTAMP(3)"))
        if left==0 and todo==0:break
        time.sleep(1)
    after={'lotsWithRemaining':left,'membersStillDue':todo,
           'expireLedgerRows':int(one(f"SELECT COUNT(*) FROM member_point_ledger WHERE tenant_id='{T}' AND action='EXPIRE'")),
           'lotsWithMoreThanOneExpire':int(one(f"SELECT COUNT(*) FROM (SELECT source_id FROM member_point_ledger WHERE tenant_id='{T}' AND action='EXPIRE' GROUP BY source_id HAVING COUNT(*)>1) d")),
           'expiredPointsTotal':int(one(f"SELECT SUM(expired) FROM member_point_lot WHERE tenant_id='{T}'")),
           'cycleAccounts':int(one(f"SELECT COUNT(*) FROM member_cycle_account WHERE tenant_id='{T}'")),
           'assessedEvents':int(one(f"SELECT COUNT(*) FROM platform_event WHERE tenant_id='{T}' AND event_type='member.cycle.assessed.v1'"))}
    say('A after restart',json.dumps(after))
    stop_app(False)
    # ---------------- B. 浸泡 ----------------
    # 超过保留期的历史事件（30天前）与Inbox，保留期开启为7天：只有这些行符合条件。
    sql(f"INSERT INTO platform_event(event_id,tenant_id,event_type,aggregate_id,aggregate_version,payload_json,status,created_at,available_at) WITH RECURSIVE s(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM s WHERE i<3000) SELECT CONCAT('{OLD}-',i),'{OLD}','order.created.v1',CONCAT('{OLD}-',i),1,'{{}}','DELIVERED',TIMESTAMPADD(DAY,-30,UTC_TIMESTAMP(3)),TIMESTAMPADD(DAY,-30,UTC_TIMESTAMP(3)) FROM s;"
        f"INSERT INTO platform_inbox(consumer_id,event_id,tenant_id) SELECT 'marketing-effects-v1',event_id,tenant_id FROM platform_event WHERE tenant_id='{OLD}';")
    start_app('b',{'COMMERCE_RETENTION_ENABLED':'true','COMMERCE_RETENTION_DELIVERED_EVENTS':'7d'})
    admin=token(T,'soak-admin','ADMIN');member=token(T,'a1','MEMBER');platform=token('platform','soak-platform','PLATFORM_OPERATOR')
    for path,body,key in [('/v1/admin/merchants',{'merchantId':'mer','name':'浸泡商家'},'mer'),('/v1/admin/stores',{'storeId':'st','merchantId':'mer','name':'浸泡店铺'},'st'),
                          ('/v1/admin/skus',{'skuId':'sku','storeId':'st','title':'浸泡商品','unitPrice':'10.00'},'sku'),('/v1/admin/inventory/receipts',{'storeId':'st','skuId':'sku','quantity':100000},'inv')]:
        assert call('POST',path,admin,body,key)[0]==200,path
    # 毒批次：会员不存在（绕过外键写入），过期时NOT_FOUND，5次后隔离。
    sql("SET SESSION foreign_key_checks=0;"+''.join(f"INSERT INTO member_point_lot(tenant_id,lot_id,member_id,policy_version,credited,remaining,expires_at) VALUES('{T}','poison{i}','ghost',1,10,10,TIMESTAMPADD(SECOND,-10,UTC_TIMESTAMP(3)));" for i in range(5))+"SET SESSION foreign_key_checks=1;")
    started=time.time();orders=0;failed_orders=0;samples=[];outage_done=recovered=replayed=False;next_sample=0
    while time.time()-started<SOAK:
        t=time.time()-started
        # 新事件：每秒约一单（报价+下单），产生order.created。
        s,q=call('POST','/v1/quotes',member,{'storeId':'st','items':[{'skuId':'sku','quantity':1}]},uuid.uuid4().hex)
        if s==200:
            s,_=call('POST','/v1/orders',member,{'quoteId':q['quoteId'],'address':{'recipient':'浸泡','phone':'13800000000','detail':'浸泡测试地址123'}},uuid.uuid4().hex)
        if s==200:orders+=1
        else:failed_orders+=1
        if not outage_done and t>=SOAK*0.3:
            say('B db outage start t=',round(t));proxy.kill();proxy.wait();time.sleep(30);start_proxy();outage_done=True;say('B db outage end')
        if not replayed and t>=SOAK*0.4:
            now=datetime.datetime.now(datetime.timezone.utc)
            s,job=call('POST','/v1/admin/runtime/replays',admin,{'jobId':'soak-replay','consumer':'marketing-effects-v1','eventTypes':['order.created.v1'],'from':(now-datetime.timedelta(hours=1)).isoformat(),'to':now.isoformat(),'mode':'REPROCESS','reason':'浸泡：重放营销效果投影'},'soak-replay')
            say('B replay created',s);replayed=True
        if not recovered and t>=SOAK*0.5:
            s,stopped=call('GET','/v1/admin/runtime/stopped?workType=member.points.expiry',admin)
            ids=[x['workId'] for x in (stopped or [])]
            s,res=call('POST','/v1/admin/runtime/recoveries',admin,{'workType':'member.points.expiry','action':'RETRY','workIds':ids or ['none'],'reason':'浸泡：数据未修复的恢复（应再次隔离）'},'soak-recover')
            say('B recovery',s,json.dumps({'ids':len(ids),'applied':(res or {}).get('applied')}));recovered=True
        if t>=next_sample:
            s,view=call('GET','/v1/platform/runtime',platform)
            db=int(one(f"SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE USER='commerce_app' AND DB='{SCHEMA}'")) if proxy.poll() is None else None
            sample={'t':round(t),'status':s,'jvm':jvm(),'dbConnections':db}
            if view:
                sample['alerts']=view.get('alerts');sample['replay']=view.get('replay');sample['retention']=(view.get('retention') or {}).get('stats')
                sample['lanes']={k:{'maxStartLag':(v.get('schedule') or {}).get('maxStartLagMillis'),'lastRun':(v.get('schedule') or {}).get('lastDurationMillis'),'breakerTrips':v['rotation']['breakerTrips'],'rotationMs':v['rotation']['lastRotationMillis'],
                                    'due':(v.get('backlog') or {}).get('due'),'quarantined':(v.get('backlog') or {}).get('quarantined')} for k,v in view['lanes'].items()}
                sample['events']={k:view['events']['health'].get(k) for k in ['due','retrying','isolated','oldestDueAgeSeconds']}
            samples.append(sample);log.write(json.dumps(sample,ensure_ascii=False)+'\n');log.flush();next_sample+=30
        time.sleep(max(0,1-(time.time()-started-t)))
    time.sleep(15)
    final={'orders':orders,'failedOrderCalls':failed_orders,
       'orderCreatedEvents':int(one(f"SELECT COUNT(*) FROM platform_event WHERE tenant_id='{T}' AND event_type='order.created.v1'")),
       'orderCreatedNotDelivered':int(one(f"SELECT COUNT(*) FROM platform_event WHERE tenant_id='{T}' AND event_type='order.created.v1' AND status<>'DELIVERED'")),
       'isolatedEvents':int(one(f"SELECT COUNT(*) FROM platform_event WHERE tenant_id='{T}' AND status='ISOLATED'")),
       'duplicateInbox':int(one(f"SELECT COUNT(*) FROM (SELECT consumer_id,event_id FROM platform_inbox WHERE tenant_id='{T}' GROUP BY consumer_id,event_id HAVING COUNT(*)>1) d")),
       'projectionRows':int(one(f"SELECT COUNT(*) FROM marketing_effect_order WHERE tenant_id='{T}'")),
       'poisonQuarantined':int(one(f"SELECT COUNT(*) FROM member_work_retry WHERE tenant_id='{T}' AND quarantined_at IS NOT NULL")),
       'poisonManualRecoveries':int(one(f"SELECT COALESCE(SUM(manual_recoveries),0) FROM member_work_retry WHERE tenant_id='{T}'")),
       'recoveryAuditRows':int(one(f"SELECT COUNT(*) FROM platform_recovery WHERE tenant_id='{T}'")),
       'oldEventsRemaining':int(one(f"SELECT COUNT(*) FROM platform_event WHERE tenant_id='{OLD}'")),'oldInboxRemaining':int(one(f"SELECT COUNT(*) FROM platform_inbox WHERE tenant_id='{OLD}'")),
       'replayJob':sql(f"SELECT status,examined,executed,already_processed,failed FROM platform_replay WHERE tenant_id='{T}'",True),
       'retryRowsNonPoison':int(one(f"SELECT COUNT(*) FROM member_work_retry WHERE tenant_id='{T}' AND item_id NOT LIKE 'poison%'"))}
    say('B final',json.dumps(final,ensure_ascii=False))
    json.dump({'run':RUN,'partA':{'mid':mid,'after':after},'samples':samples,'final':final},open(f'{OUT}/live-soak.json','w'),ensure_ascii=False,indent=1)
finally:
    stop_app(False)
    if proxy and proxy.poll() is None:proxy.kill()
