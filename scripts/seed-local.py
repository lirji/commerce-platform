#!/usr/bin/env python3
"""通过真实API灌入本地演示数据；访问令牌只保存本地私密文件。"""
from pathlib import Path
import hashlib,json,os,secrets,shlex,subprocess,urllib.request
root=Path(__file__).resolve().parents[1]
env={}
for line in (root/'.local/runtime.env').read_text().splitlines():
    key,value=line.removeprefix('export ').split('=',1);env[key]=shlex.split(value)[0]
credentials=root/'.local/demo-access.json'
if credentials.exists(): access=json.loads(credentials.read_text())
else:
    access={'adminToken':secrets.token_hex(32),'memberToken':secrets.token_hex(32),'baseUrl':'http://127.0.0.1:8600'}
    fd=os.open(credentials,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
    with os.fdopen(fd,'w') as f: json.dump(access,f,indent=2)
sql=''
for field,actor,role in [('adminToken','demo-admin','ADMIN'),('memberToken','demo-buyer','MEMBER')]:
    digest=hashlib.sha256(access[field].encode()).hexdigest()
    sql+=f"INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES('{digest}','demo','{actor}','{role}','2030-01-01') ON DUPLICATE KEY UPDATE token_hash=token_hash;\n"
process_env=dict(os.environ,MYSQL_PWD=env['COMMERCE_DB_PASSWORD'])
p=subprocess.run(['docker','exec','-i','-e','MYSQL_PWD','dev-infra-mysql84-1','mysql','-ucommerce_app','commerce_local'],input=sql,text=True,capture_output=True,env=process_env,timeout=15)
if p.returncode: raise SystemExit('Credential seed failed; no secret output. Database must already be migrated.')
def post(path,key,body,member=False):
    token=access['memberToken' if member else 'adminToken']
    req=urllib.request.Request(access['baseUrl']+path,data=json.dumps(body).encode(),headers={'Authorization':'Bearer '+token,'Content-Type':'application/json','Idempotency-Key':key})
    try:
        with urllib.request.urlopen(req,timeout=15) as response: return json.load(response)
    except urllib.error.HTTPError as e: raise SystemExit(f'Seed failed: {path} HTTP {e.code}; credentials omitted.')
post('/v1/admin/members','seed-member',{'memberId':'member-demo','actorId':'demo-buyer','displayName':'演示会员','memberLevel':'VIP'})
post('/v1/admin/merchants','seed-merchant',{'merchantId':'merchant-demo','name':'统一电商示范商家'})
post('/v1/admin/stores','seed-store',{'storeId':'store-demo','merchantId':'merchant-demo','name':'城市生活旗舰店'})
for sku,title,price in [('sku-coffee','精品咖啡礼盒','129.00'),('sku-mug','陶瓷随行杯','59.00'),('sku-bag','城市通勤包','239.00')]:
    post('/v1/admin/skus','seed-'+sku,{'skuId':sku,'storeId':'store-demo','title':title,'unitPrice':price})
post('/v1/admin/campaigns','seed-campaign',{'campaignId':'vip-welcome','version':1,'storeId':'store-demo','name':'会员满100减20','validFrom':'2026-01-01T00:00:00Z','validTo':'2030-01-01T00:00:00Z','minimumSpend':'100.00','discountAmount':'20.00','rule':{'kind':'COMPARE','field':'memberLevel','operator':'EQ','valueType':'TEXT','value':'VIP'}})
post('/v1/admin/campaigns/vip-welcome/1/publish','seed-publish',{'expectedVersion':0})
quote=post('/v1/quotes','seed-quote',{'storeId':'store-demo','items':[{'skuId':'sku-coffee','quantity':1},{'skuId':'sku-mug','quantity':1}]},True)
(root/'.local/seed-quote.json').write_text(json.dumps(quote,ensure_ascii=False,indent=2))
print('Demo data persisted through APIs; quote='+quote['quoteId']+' payable='+quote['payable']+'. Access tokens are in .local/demo-access.json (not printed).')
