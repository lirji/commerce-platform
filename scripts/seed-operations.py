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
path=root/'.local/operations-access.json'
if path.exists() and '--fresh' not in sys.argv:
    access=json.loads(path.read_text())
    if access['schema']!=schema or access['baseUrl']!=base:raise SystemExit('Existing fixture targets another environment; use --fresh explicitly.')
else:
    access={'tenant':'operations-'+str(uuid.uuid4()),'adminToken':secrets.token_hex(32),'memberToken':secrets.token_hex(32),'operatorToken':secrets.token_hex(32),'baseUrl':base,'schema':schema,'storeId':'brand-store','seedAt':datetime.datetime.now(datetime.timezone.utc).isoformat()}
    fd=os.open(path,os.O_CREAT|os.O_TRUNC|os.O_WRONLY,0o600)
    with os.fdopen(fd,'w') as f:json.dump(access,f,indent=2)
# 仅身份夹具直写凭据表，业务数据一律通过API持久化；不将明文凭据写入SQL或日志。
sql=''
for field,actor,role in [('adminToken','ops-admin','ADMIN'),('memberToken','ops-buyer','MEMBER'),('operatorToken','ops-clerk','OPERATOR')]:
    digest=hashlib.sha256(access[field].encode()).hexdigest()
    sql+=f"INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES('{digest}','{access['tenant']}','{actor}','{role}',DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 1 DAY)) ON DUPLICATE KEY UPDATE expires_at=DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 1 DAY);\n"
p=subprocess.run(['docker','exec','-i','-e','MYSQL_PWD',os.getenv('COMMERCE_MYSQL_CONTAINER','dev-infra-mysql84-1'),'mysql','-ucommerce_app',schema],input=sql,text=True,capture_output=True,env=dict(os.environ,MYSQL_PWD=env['COMMERCE_DB_PASSWORD']),timeout=15)
if p.returncode:raise SystemExit('Could not provision fixture identities; private diagnostics suppressed.')
sequence=0
def post(route,body=None):
    global sequence
    sequence+=1
    req=urllib.request.Request(base+'/v1'+route,data=json.dumps(body).encode(),headers={'Authorization':'Bearer '+access['adminToken'],'Content-Type':'application/json','Idempotency-Key':'operations-seed-'+str(sequence)})
    try:
        with urllib.request.urlopen(req,timeout=15) as response:return json.load(response)
    except urllib.error.HTTPError as e:raise SystemExit(f'Operations seed failed: {route} HTTP {e.code}; no credentials logged.')
now=datetime.datetime.fromisoformat(access['seedAt'])
def at(seconds):return (now+datetime.timedelta(seconds=seconds)).isoformat()
def publish(kind,id):
    for n,action in enumerate(['submit','approve','publish']):post('/admin/'+kind+'/'+id+'/1/'+action,{'expectedVersion':n})
post('/admin/merchants',{'merchantId':'brand','name':'日常品牌'})
for id,name in [('brand-store','品牌直营旗舰店'),('other-store','未授权分店')]:post('/admin/stores',{'storeId':id,'merchantId':'brand','name':name})
post('/admin/store-grants',{'grantId':'clerk-catalog','actorId':'ops-clerk','resourceType':'STORE','resourceId':'brand-store','permission':'CATALOG','reason':'演示商品经营授权'})
post('/admin/member-growth/policies',{'version':1,'effectiveFrom':at(0),'growthPerYuan':'1.00','levels':[{'code':'BASIC','minimumGrowth':0},{'code':'GOLD','minimumGrowth':100},{'code':'PLATINUM','minimumGrowth':500}]})
post('/admin/members',{'memberId':'ops-member','actorId':'ops-buyer','displayName':'品牌成长会员','memberLevel':'BASIC'})
post('/admin/members',{'memberId':'lifecycle-member','actorId':'lifecycle-buyer','displayName':'生命周期验收会员','memberLevel':'BASIC'})
post('/admin/member-tags',{'tagId':'coffee-lover','name':'咖啡爱好者'})
post('/admin/member-tags/ops-member/assign',{'tagId':'coffee-lover','active':True,'expectedVersion':0,'reason':'会员偏好演示'})
post('/operations/products',{'productId':'coffee-product','storeId':'brand-store','title':'日常精品咖啡','category':'咖啡','brand':'日常品牌'})
post('/operations/skus',{'skuId':'coffee-small','productId':'coffee-product','storeId':'brand-store','title':'精品咖啡小盒','unitPrice':'100.00','specifications':[{'name':'包装','value':'小盒'},{'name':'烘焙','value':'中度'}]})
post('/operations/skus/coffee-small',{'storeId':'brand-store','expectedVersion':1,'title':'精品咖啡小盒','unitPrice':'100.00','status':'ACTIVE','reason':'演示首发上架'})
post('/admin/inventory/receipts',{'storeId':'brand-store','skuId':'coffee-small','quantity':100})
rule={'kind':'COMPARE','field':'memberGrowth','operator':'GTE','valueType':'DECIMAL','value':'100'}
post('/admin/segments',{'segmentId':'growing-members','version':1,'name':'高成长会员','rule':rule,'ttlSeconds':3600,'refreshSeconds':600,'maxMembers':10000})
post('/admin/campaigns',{'campaignId':'coffee-growth','version':1,'storeId':'brand-store','name':'咖啡会员阶梯礼遇','validFrom':at(-60),'validTo':at(86400*7),'minimumSpend':'100.00','discountAmount':'20.00','rule':{'kind':'COMPARE','field':'memberTags','operator':'CONTAINS','valueType':'TEXT','value':'coffee-lover'},'policy':{'terms':{'percentageBps':0,'platformFundingBps':5000,'budget':'2000.00','pricing':{'includedSkuIds':['coffee-small'],'excludedSkuIds':[],'tiers':[{'minimumSpend':'100.00','discountAmount':'10.00','percentageBps':0},{'minimumSpend':'200.00','discountAmount':'20.00','percentageBps':0}]}}}})
publish('campaigns','coffee-growth')
post('/admin/journeys',{'journeyId':'growth-welcome','version':1,'storeId':'brand-store','name':'金卡升级关怀','trigger':'LEVEL_CHANGED','validFrom':at(-60),'validTo':at(86400*7),'maxDurationSeconds':3600,'entry':'notify','controls':{'entryRule':{'kind':'COMPARE','field':'memberLevel','operator':'EQ','valueType':'TEXT','value':'GOLD'},'maxEntries':1,'entryWindowSeconds':86400,'notificationLimit':1,'notificationWindowSeconds':86400},'nodes':[{'id':'notify','kind':'NOTIFY','next':'end','title':'欢迎成为金卡会员','body':'成长已达标，欢迎体验咖啡会员礼遇。'},{'id':'end','kind':'END'}]})
publish('journeys','growth-welcome')
print('Operations fixture persisted through real APIs. Private access: .local/operations-access.json (not printed).')
