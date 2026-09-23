#!/usr/bin/env python3
"""为本地浏览器验收建立独立租户；只写项目库，凭据不输出。"""
from pathlib import Path
import hashlib,json,os,secrets,shlex,subprocess,urllib.request,uuid,datetime
root=Path(__file__).resolve().parents[1]
env={}
for line in (root/'.local/runtime.env').read_text().splitlines():
    key,value=line.removeprefix('export ').split('=',1);env[key]=shlex.split(value)[0]
if '/commerce_local?' not in env['COMMERCE_DB_URL']:raise SystemExit('Only the project local schema is allowed.')
tenant='browser-'+str(uuid.uuid4());access={'tenant':tenant,'adminToken':secrets.token_hex(32),'memberToken':secrets.token_hex(32),'baseUrl':os.getenv('COMMERCE_E2E_BASE_URL','http://127.0.0.1:8600'),'storeId':'browser-store'}
sql=''
for field,actor,role in [('adminToken','browser-admin','ADMIN'),('memberToken','browser-buyer','MEMBER')]:
    digest=hashlib.sha256(access[field].encode()).hexdigest();sql+=f"INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES('{digest}','{tenant}','{actor}','{role}',DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 1 DAY));\n"
p=subprocess.run(['docker','exec','-i','-e','MYSQL_PWD',os.getenv('COMMERCE_MYSQL_CONTAINER','dev-infra-mysql84-1'),'mysql','-ucommerce_app','commerce_local'],input=sql,text=True,capture_output=True,env=dict(os.environ,MYSQL_PWD=env['COMMERCE_DB_PASSWORD']),timeout=15)
if p.returncode:raise SystemExit('Browser credentials could not be provisioned; details suppressed.')
path=root/'.local/e2e-access.json';fd=os.open(path,os.O_CREAT|os.O_TRUNC|os.O_WRONLY,0o600)
with os.fdopen(fd,'w') as f:json.dump(access,f,indent=2)
def post(path,body,key=None):
    req=urllib.request.Request(access['baseUrl']+path,data=json.dumps(body).encode(),headers={'Authorization':'Bearer '+access['adminToken'],'Content-Type':'application/json','Idempotency-Key':key or str(uuid.uuid4())})
    try:
        with urllib.request.urlopen(req,timeout=15) as response:return json.load(response)
    except urllib.error.HTTPError as e:raise SystemExit(f'Fixture API failed: {path} HTTP {e.code}, no credentials logged.')
now=datetime.datetime.now(datetime.timezone.utc)
def at(seconds):return (now+datetime.timedelta(seconds=seconds)).isoformat()
post('/v1/admin/members',{'memberId':'browser-member','actorId':'browser-buyer','displayName':'浏览器验收会员','memberLevel':'VIP'})
post('/v1/admin/merchants',{'merchantId':'browser-merchant','name':'全链路验收商家'})
post('/v1/admin/stores',{'storeId':'browser-store','merchantId':'browser-merchant','name':'全链路验收旗舰店'})
for sku,title,price in [('coffee','精品咖啡礼盒','129.00'),('mug','陶瓷随行杯','59.00'),('bag','城市通勤包','239.00')]:
    post('/v1/admin/skus',{'skuId':sku,'storeId':'browser-store','title':title,'unitPrice':price})
    post('/v1/admin/inventory/receipts',{'storeId':'browser-store','skuId':sku,'quantity':100})
post('/v1/admin/entitlement-definitions',{'benefitId':'experience','version':1,'storeId':'browser-store','name':'会员体验权益','units':3,'quota':100,'validFrom':at(-3600),'validTo':at(86400),'validityDays':7})
post('/v1/admin/audiences',{'audienceId':'vip','version':1,'name':'会员验收人群','source':'browser-fixture','watermark':at(-60),'validUntil':at(3600),'memberIds':['browser-member']})
post('/v1/admin/campaigns',{'campaignId':'welcome','version':1,'storeId':'browser-store','name':'会员满100减20','validFrom':at(-60),'validTo':at(3600),'minimumSpend':'100.00','discountAmount':'20.00','rule':{'kind':'COMPARE','field':'memberLevel','operator':'EQ','valueType':'TEXT','value':'VIP'},'policy':{'audience':{'id':'vip','version':1},'terms':{'percentageBps':0,'platformFundingBps':5000,'budget':'2000.00','grant':{'benefitId':'experience','version':1}}}})
for n,action in enumerate(['submit','approve','publish']):post('/v1/admin/campaigns/welcome/1/'+action,{'expectedVersion':n})
post('/v1/admin/coupon-definitions',{'definitionId':'welcome-coupon','version':1,'storeId':'browser-store','name':'新会员加享券','minimumSpend':'100.00','discountAmount':'5.00','validFrom':at(-60),'validTo':at(3600),'quota':100,'stackable':True})
print('Browser fixture persisted in a dedicated tenant; credentials stored in .local/e2e-access.json, not printed.')
