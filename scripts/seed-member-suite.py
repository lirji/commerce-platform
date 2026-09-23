#!/usr/bin/env python3
"""经真实API建立经营演示；重复运行回放同一批命令，--fresh创建新隔离租户供验收。"""
from pathlib import Path
import datetime,hashlib,json,os,secrets,shlex,subprocess,sys,urllib.request,urllib.error,uuid
root=Path(__file__).resolve().parents[1]
env={}
for line in (root/'.local/runtime.env').read_text().splitlines():
    if not line.strip() or line.lstrip().startswith('#'):continue
    key,value=line.removeprefix('export ').split('=',1);env[key]=shlex.split(value)[0]
schema=os.getenv('COMMERCE_E2E_SCHEMA','commerce_local')
if schema not in ('commerce_local','commerce_test_20260923'):raise SystemExit('Only project local/test schemas are allowed.')
url=env['COMMERCE_TEST_DB_URL'] if schema=='commerce_test_20260923' else env['COMMERCE_DB_URL']
if '/'+schema+'?' not in url:raise SystemExit('Schema/configuration mismatch.')
base=os.getenv('COMMERCE_E2E_BASE_URL','http://127.0.0.1:8600')
if not base.startswith(('http://127.0.0.1:','http://localhost:')):raise SystemExit('Demo fixtures require a local API.')
path=root/'.local/member-suite-access.json'
if path.exists() and '--fresh' not in sys.argv:
    access=json.loads(path.read_text())
    if access['schema']!=schema or access['baseUrl']!=base:raise SystemExit('Existing fixture targets another environment; use --fresh explicitly.')
else:
    access={'tenant':'member-suite-'+str(uuid.uuid4()),'adminToken':secrets.token_hex(32),'memberToken':secrets.token_hex(32),'operatorToken':secrets.token_hex(32),'checkoutToken':secrets.token_hex(32),'exchangeToken':secrets.token_hex(32),'baseUrl':base,'schema':schema,'storeId':'brand-store','seedAt':datetime.datetime.now(datetime.timezone.utc).isoformat()}
    fd=os.open(path,os.O_CREAT|os.O_TRUNC|os.O_WRONLY,0o600)
    with os.fdopen(fd,'w') as f:json.dump(access,f,indent=2)
# 仅身份夹具直写凭据表，业务数据一律通过API持久化；不将明文凭据写入SQL或日志。
sql=''
for field,actor,role in [('adminToken','ops-admin','ADMIN'),('memberToken','ops-buyer','MEMBER'),('operatorToken','ops-clerk','OPERATOR'),('checkoutToken','points-buyer','MEMBER'),('exchangeToken','exchange-buyer','MEMBER')]:
    digest=hashlib.sha256(access[field].encode()).hexdigest()
    sql+=f"INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES('{digest}','{access['tenant']}','{actor}','{role}',DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 1 DAY)) ON DUPLICATE KEY UPDATE expires_at=DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 1 DAY);\n"
p=subprocess.run(['docker','exec','-i','-e','MYSQL_PWD',os.getenv('COMMERCE_MYSQL_CONTAINER','dev-infra-mysql84-1'),'mysql','-ucommerce_app',schema],input=sql,text=True,capture_output=True,env=dict(os.environ,MYSQL_PWD=env['COMMERCE_DB_PASSWORD']),timeout=15)
if p.returncode:raise SystemExit('Could not provision fixture identities; private diagnostics suppressed.')
sequence=0
def post(route,body=None):
    global sequence
    sequence+=1
    req=urllib.request.Request(base+'/v1'+route,data=json.dumps(body).encode(),headers={'Authorization':'Bearer '+access['adminToken'],'Content-Type':'application/json','Idempotency-Key':'member-suite-seed-'+str(sequence)})
    try:
        with urllib.request.urlopen(req,timeout=15) as response:return json.load(response)
    except urllib.error.HTTPError as e:raise SystemExit(f'Operations seed failed: {route} HTTP {e.code}; no credentials logged.')
now=datetime.datetime.fromisoformat(access['seedAt'])
def at(seconds):return (now+datetime.timedelta(seconds=seconds)).isoformat()
def publish(kind,id):
    for n,action in enumerate(['submit','approve','publish']):post('/admin/'+kind+'/'+id+'/1/'+action,{'expectedVersion':n})
post('/admin/merchants',{'merchantId':'brand','name':'日常品牌'})
post('/admin/stores',{'storeId':'brand-store','merchantId':'brand','name':'品牌直营旗舰店'})
post('/admin/members',{'memberId':'suite-member','actorId':'ops-buyer','displayName':'周期礼遇会员','memberLevel':'BASIC'})
post('/admin/member-growth/policies',{'version':1,'effectiveFrom':at(0),'growthPerYuan':'1.00','levels':[{'code':'BASIC','minimumGrowth':0},{'code':'GOLD','minimumGrowth':100}]})
post('/admin/member-cycles/policies',{'version':1,'effectiveFrom':at(0),'periodDays':30,'levels':[{'code':'BASIC','minimumGrowth':0},{'code':'GOLD','minimumGrowth':100},{'code':'PLATINUM','minimumGrowth':500}]})
post('/admin/entitlement-definitions',{'benefitId':'monthly-coffee','version':1,'storeId':'brand-store','name':'金卡每月咖啡礼遇','units':2,'quota':1000,'validFrom':at(-60),'validTo':at(86400*365),'validityDays':30})
post('/admin/member-cycle-benefits',{'bindingId':'gold-monthly','policyVersion':1,'level':'GOLD','storeId':'brand-store','validUntil':at(86400*300),'benefits':[{'benefitId':'monthly-coffee','version':1}]})
post('/admin/member-growth/suite-member/adjust',{'expectedVersion':0,'delta':120,'reason':'隔离演示周期成长'})
post('/admin/member-cycle-benefits/suite-member/grant')
post('/admin/member-points/policies',{'version':1,'effectiveFrom':at(0),'earnPerYuan':'1.00','expiryDays':30,'spendEnabled':True,'pointsPerYuan':100,'maxDeductionBps':5000})
post('/admin/member-points/suite-member/adjust',{'expectedVersion':0,'delta':1200,'reason':'隔离演示积分入账'})
post('/admin/members',{'memberId':'checkout-member','actorId':'points-buyer','displayName':'积分购物会员','memberLevel':'BASIC'})
post('/admin/member-points/checkout-member/adjust',{'expectedVersion':0,'delta':2000,'reason':'隔离积分结算验收'})
post('/operations/products',{'productId':'points-coffee','storeId':'brand-store','title':'积分精品咖啡','category':'咖啡','brand':'日常品牌'})
post('/operations/skus',{'skuId':'points-coffee','productId':'points-coffee','storeId':'brand-store','title':'积分精品咖啡','unitPrice':'25.00','specifications':[{'name':'包装','value':'小盒'}]})
post('/operations/skus/points-coffee',{'storeId':'brand-store','expectedVersion':1,'title':'积分精品咖啡','unitPrice':'25.00','status':'ACTIVE','reason':'积分结算演示上架'})
post('/admin/inventory/receipts',{'storeId':'brand-store','skuId':'points-coffee','quantity':100})
post('/admin/members',{'memberId':'exchange-member','actorId':'exchange-buyer','displayName':'积分兑换会员','memberLevel':'BASIC'})
post('/admin/member-points/exchange-member/adjust',{'expectedVersion':0,'delta':2000,'reason':'隔离兑换验收'})
post('/admin/coupon-definitions',{'definitionId':'points-exclusive','version':1,'storeId':'brand-store','name':'积分专享五元券','minimumSpend':'0.00','discountAmount':'5.00','validFrom':at(-60),'validTo':at(86400*60),'quota':1000,'stackable':True,'platformFundingBps':10000,'issuanceMode':'SOURCE_ONLY'})
for id,name,kind,asset,cost in [('exclusive-coupon','积分专享五元券','COUPON','points-exclusive',200),('coffee-right','咖啡双杯礼遇','ENTITLEMENT','monthly-coffee',500)]:
    post('/admin/point-offers',{'offerId':id,'storeId':'brand-store','name':name,'kind':kind,'assetId':asset,'assetVersion':1,'points':cost,'quota':100,'perMemberLimit':2,'validFrom':at(0),'validTo':at(86400*30)})
# 演示仅受理后不假称到账；正式消费者读取持久事件完成发放。
for _ in range(6):
    req=urllib.request.Request(base+'/v1/admin/events/pump',data=b'null',headers={'Authorization':'Bearer '+access['adminToken'],'Content-Type':'application/json'})
    with urllib.request.urlopen(req,timeout=15) as response:json.load(response)
print('Member suite fixture persisted. Private access: .local/member-suite-access.json (not printed).')
