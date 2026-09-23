#!/usr/bin/env python3
"""通过真实API灌入本地演示数据；访问令牌只保存本地私密文件。"""
from pathlib import Path
import hashlib,json,os,secrets,shlex,subprocess,urllib.request,datetime,time
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
access['baseUrl']=os.getenv('COMMERCE_BASE_URL',access['baseUrl'])
credentials.write_text(json.dumps(access,indent=2))
sql=''
for field,actor,role in [('adminToken','demo-admin','ADMIN'),('memberToken','demo-buyer','MEMBER')]:
    digest=hashlib.sha256(access[field].encode()).hexdigest()
    sql+=f"INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES('{digest}','demo','{actor}','{role}','2030-01-01') ON DUPLICATE KEY UPDATE token_hash=token_hash;\n"
process_env=dict(os.environ,MYSQL_PWD=env['COMMERCE_DB_PASSWORD'])
p=subprocess.run(['docker','exec','-i','-e','MYSQL_PWD','dev-infra-mysql84-1','mysql','-ucommerce_app','commerce_local'],input=sql,text=True,capture_output=True,env=process_env,timeout=15)
if p.returncode: raise SystemExit('Credential seed failed; no secret output. Database must already be migrated.')
def post(path,key,body=None,member=False):
    token=access['memberToken' if member else 'adminToken']
    req=urllib.request.Request(access['baseUrl']+path,data=json.dumps(body).encode() if body is not None else b'',headers={'Authorization':'Bearer '+token,'Content-Type':'application/json','Idempotency-Key':key})
    try:
        with urllib.request.urlopen(req,timeout=15) as response: return json.load(response)
    except urllib.error.HTTPError as e: raise SystemExit(f'Seed failed: {path} HTTP {e.code}; credentials omitted.')
post('/v1/admin/members','seed-member',{'memberId':'member-demo','actorId':'demo-buyer','displayName':'演示会员','memberLevel':'VIP'})
post('/v1/admin/merchants','seed-merchant',{'merchantId':'merchant-demo','name':'统一电商示范商家'})
post('/v1/admin/stores','seed-store',{'storeId':'store-demo','merchantId':'merchant-demo','name':'城市生活旗舰店'})
for sku,title,price in [('sku-coffee','精品咖啡礼盒','129.00'),('sku-mug','陶瓷随行杯','59.00'),('sku-bag','城市通勤包','239.00')]:
    post('/v1/admin/skus','seed-'+sku,{'skuId':sku,'storeId':'store-demo','title':title,'unitPrice':price})
for sku in ['sku-coffee','sku-mug','sku-bag']:
    post('/v1/admin/inventory/receipts','seed-stock-'+sku,{'storeId':'store-demo','skuId':sku,'quantity':100})
post('/v1/admin/campaigns','seed-campaign',{'campaignId':'vip-welcome','version':1,'storeId':'store-demo','name':'会员满100减20','validFrom':'2026-01-01T00:00:00Z','validTo':'2030-01-01T00:00:00Z','minimumSpend':'100.00','discountAmount':'20.00','rule':{'kind':'COMPARE','field':'memberLevel','operator':'EQ','valueType':'TEXT','value':'VIP'}})
post('/v1/admin/campaigns/vip-welcome/1/publish','seed-publish',{'expectedVersion':0})
quote=post('/v1/quotes','seed-quote',{'storeId':'store-demo','items':[{'skuId':'sku-coffee','quantity':1},{'skuId':'sku-mug','quantity':1}]},True)
(root/'.local/seed-quote.json').write_text(json.dumps(quote,ensure_ascii=False,indent=2))
print('Demo data persisted through APIs; quote='+quote['quoteId']+' payable='+quote['payable']+'. Access tokens are in .local/demo-access.json (not printed).')

# 同一UTC日期使用固定版本与输入；重复执行回放已有命令，新日期刷新可信人群。
now=datetime.datetime.now(datetime.timezone.utc);day=now.replace(hour=0,minute=0,second=0,microsecond=0)
revision=int(day.strftime('%Y%m%d'));suffix=str(revision)
def get(path,member=False):
    req=urllib.request.Request(access['baseUrl']+path,headers={'Authorization':'Bearer '+access['memberToken' if member else 'adminToken']})
    with urllib.request.urlopen(req,timeout=15) as response:return json.load(response)
def settle(path,status,member=False):
    for _ in range(12):
        post('/v1/admin/events/pump','pump-no-command')
        value=get(path,member)
        if value['status']==status:return value
    raise SystemExit('Seed did not reach its expected durable state: '+path)
if not get('/v1/runtime-capabilities')['sandboxEnabled']:raise SystemExit('Extended demo seed requires the explicitly enabled local sandbox.')
post('/v1/admin/entitlement-definitions','seed-entitlement',{'benefitId':'member-experience','version':1,'storeId':'store-demo','name':'会员体验权益','units':3,'quota':10000,'validFrom':'2026-01-01T00:00:00Z','validTo':'2031-01-01T00:00:00Z','validityDays':7})
post('/v1/admin/rules','seed-vip-rule',{'ruleId':'vip-rule','version':1,'name':'VIP会员规则','rule':{'kind':'COMPARE','field':'memberLevel','operator':'EQ','valueType':'TEXT','value':'VIP'}})
post('/v1/admin/rules/vip-rule/1/publish','seed-vip-rule-publish')
post('/v1/admin/audiences','seed-audience-'+suffix,{'audienceId':'daily-vip','version':revision,'name':'每日VIP会员快照','source':'local-demo-database-seed','watermark':day.isoformat(),'validUntil':(day+datetime.timedelta(days=1)).isoformat(),'memberIds':['member-demo']})
post('/v1/admin/campaigns','seed-enhanced-'+suffix,{'campaignId':'member-benefits','version':revision,'storeId':'store-demo','name':'会员满100减25并赠权益','validFrom':'2026-01-01T00:00:00Z','validTo':'2030-01-01T00:00:00Z','minimumSpend':'100.00','discountAmount':'25.00','rule':None,'policy':{'audience':{'id':'daily-vip','version':revision},'rule':{'id':'vip-rule','version':1},'terms':{'percentageBps':0,'platformFundingBps':5000,'budget':'10000.00','grant':{'benefitId':'member-experience','version':1}}}})
for expected,action in enumerate(['submit','approve','publish']):post(f'/v1/admin/campaigns/member-benefits/{revision}/{action}','seed-enhanced-'+action+'-'+suffix,{'expectedVersion':expected})
post('/v1/admin/coupon-definitions','seed-coupon-definition',{'definitionId':'member-gift','version':1,'storeId':'store-demo','name':'会员满100减10加享券','minimumSpend':'100.00','discountAmount':'10.00','validFrom':'2026-01-01T00:00:00Z','validTo':'2030-01-01T00:00:00Z','quota':10000,'stackable':True,'platformFundingBps':10000})
post('/v1/coupons/member-gift/1/claim','seed-claim-demo',None,True)
post('/v1/admin/journeys','seed-journey',{'journeyId':'member-care','version':1,'storeId':'store-demo','name':'支付后会员关怀','trigger':'ORDER_PAID','validFrom':'2026-01-01T00:00:00Z','validTo':'2030-01-01T00:00:00Z','maxDurationSeconds':3600,'entry':'wait','nodes':[{'id':'wait','kind':'WAIT','seconds':1,'next':'notify'},{'id':'notify','kind':'NOTIFY','title':'感谢您选择城市生活旗舰店','body':'您的订单已经付款，会员权益可在权益中心查看。','next':'end'},{'id':'end','kind':'END'}]})
for expected,action in enumerate(['submit','approve','publish']):post('/v1/admin/journeys/member-care/1/'+action,'seed-journey-'+action,{'expectedVersion':expected})
post('/v1/admin/ops-pages','seed-ops-page',{'pageId':'member-operations','version':1,'title':'会员营销运营台','storeId':'store-demo','sections':[{'id':'campaigns','title':'会员活动','source':'CAMPAIGNS'},{'id':'budgets','title':'预算使用','source':'BUDGETS'},{'id':'benefits','title':'权益发行','source':'ENTITLEMENTS'},{'id':'journeys','title':'自动化旅程','source':'JOURNEYS'}],'actions':[{'id':'create-coupon','label':'新建会员优惠券','kind':'CREATE_COUPON'},{'id':'create-campaign','label':'新建营销活动','kind':'CREATE_CAMPAIGN'}]})
for expected,action in enumerate(['submit','approve','publish']):post('/v1/admin/ops-pages/member-operations/1/'+action,'seed-ops-'+action,{'expectedVersion':expected})
def paid_order(label,items):
    q=post('/v1/quotes','seed-'+label+'-quote-'+suffix,{'storeId':'store-demo','items':items},True)
    order=post('/v1/orders','seed-'+label+'-order-'+suffix,{'quoteId':q['quoteId'],'address':{'recipient':'本地演示会员','phone':'13800000000','detail':'隔离演示地址，不用于真实发货'}},True)
    current=get('/v1/orders/'+order['orderId'],True)
    if current['status'] in ['PENDING_PAYMENT','PAYMENT_IN_PROGRESS']:
        payment=post('/v1/orders/'+order['orderId']+'/payments','seed-'+label+'-payment-'+suffix,None,True)
        post('/v1/admin/sandbox/payments/'+payment['paymentId']+'/fact','seed-'+label+'-paid-'+suffix,{'status':'PAID'})
        post('/v1/admin/orders/'+order['orderId']+'/payment/reconcile','reconcile-no-command')
        settle('/v1/orders/'+order['orderId'],'PAID',True)
    return order['orderId']
completed=paid_order('completed',[{'skuId':'sku-coffee','quantity':1},{'skuId':'sku-mug','quantity':1}])
for _ in range(3):post('/v1/admin/events/pump','pump-no-command')
post('/v1/admin/fulfillments/'+completed+'/ship','seed-completed-ship-'+suffix,{'trackingNo':'DEMO-'+suffix})
post('/v1/admin/fulfillments/'+completed+'/deliver','seed-completed-deliver-'+suffix)
settle('/v1/orders/'+completed,'COMPLETED',True)
refunded=paid_order('refunded',[{'skuId':'sku-coffee','quantity':1}])
case=post('/v1/aftersales','seed-return-'+suffix,{'orderId':refunded,'reason':'本地全链路售后演示','items':[{'skuId':'sku-coffee','quantity':1}]},True)
approved=post('/v1/admin/aftersales/'+case['caseId']+'/approve','seed-return-approve-'+suffix)
post('/v1/admin/sandbox/refunds/'+approved['refundId']+'/success','seed-refund-success-'+suffix)
post('/v1/admin/refunds/'+approved['refundId']+'/reconcile','reconcile-no-command')
settle('/v1/aftersales/'+case['caseId'],'COMPLETED',True)
for _ in range(3):post('/v1/admin/events/pump','pump-no-command')
# 等待节点使用持久到期时间；仅触发有限批次，不伪造通知或直接写业务表。
for _ in range(4):
    post('/v1/admin/journeys/pump','pump-no-command');time.sleep(0.4)
print('Extended demo persisted: governed assets, coupons, benefits, journey, ops page, completed order and refunded order. Same-day rerun is idempotent.')
