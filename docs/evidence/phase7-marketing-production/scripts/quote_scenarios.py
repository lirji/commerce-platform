import concurrent.futures as f,hashlib,json,subprocess,time,uuid
from pathlib import Path
from datetime import datetime,timedelta,timezone
from urllib.request import Request,urlopen
from urllib.error import HTTPError
tokens=json.loads(Path('.local/phase7-bench/tokens.json').read_text())
def call(path,token,body):
 start=time.perf_counter(); req=Request('http://127.0.0.1:8623'+path,data=json.dumps(body).encode(),headers={'Authorization':'Bearer '+token,'Idempotency-Key':str(uuid.uuid4()),'Content-Type':'application/json'},method='POST')
 with urlopen(req,timeout=30) as r: return json.loads(r.read()),(time.perf_counter()-start)*1000
std={}
for tier in ['small','large']:
 ident='standard-'+uuid.uuid4().hex[:8]; token=str(uuid.uuid4())
 call('/v1/admin/members',tokens[tier]['admin'],{'memberId':ident,'actorId':ident,'displayName':'负例压测','memberLevel':'STANDARD'})
 query=f"INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES('{hashlib.sha256(token.encode()).hexdigest()}','phase7-{tier}','{ident}','MEMBER','2030-01-01');"
 subprocess.run(['docker','exec','-i','dev-infra-mysql84-1','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot commerce_phase7_bench'],input=query.encode(),check=True)
 std[tier]=token
now=datetime.now(timezone.utc)
when=lambda s:(now+timedelta(seconds=s)).isoformat().replace('+00:00','Z')
ident='std-match-'+uuid.uuid4().hex[:8]
call('/v1/admin/campaigns',tokens['large']['admin'],{'campaignId':ident,'version':1,'storeId':'store1','name':'单命中','validFrom':when(-60),'validTo':when(3600),'minimumSpend':'20.00','discountAmount':'1.00','rule':{'kind':'COMPARE','field':'memberLevel','operator':'EQ','valueType':'TEXT','value':'STANDARD'}})
call('/v1/admin/campaigns/'+ident+'/1/publish',tokens['large']['admin'],{'expectedVersion':0})
# Load stores were created by the canonical provider workload.
query="SELECT MAX(store_id) FROM benefit_coupon_definition WHERE tenant_id='phase7-small'"
store=subprocess.check_output(['docker','exec','dev-infra-mysql84-1','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot commerce_phase7_bench -Nse "'+query+'"']).decode().strip()
scenarios=[('no_match',std['small'],'store1','sku1',120),('one_match_among_81',std['large'],'store1','sku1',120),('audience_miss',tokens['small']['member'],store,store,120)]
print('scenario,samples,p50_ms,p95_ms,p99_ms,throughput_per_second,selected,trace_candidates')
for name,token,store,sku,samples in scenarios:
 def q(_):return call('/v1/quotes',token,{'storeId':store,'items':[{'skuId':sku,'quantity':1}]})
 for i in range(5): q(i)
 start=time.perf_counter()
 with f.ThreadPoolExecutor(max_workers=8) as p: rows=list(p.map(q,range(samples)))
 duration=time.perf_counter()-start; times=sorted(r[1] for r in rows); perc=lambda x:times[round((samples-1)*x)]
 selected=sum(bool(r[0].get('campaign')) for r in rows)
 print(f'{name},{samples},{perc(.5):.2f},{perc(.95):.2f},{perc(.99):.2f},{samples/duration:.1f},{selected},{len(rows[0][0]["trace"])}')
 if name=='one_match_among_81':assert selected==120
 else:assert selected==0
start=time.perf_counter()
def mixed(n):
 tier=['small','medium','large'][n%3]
 return call('/v1/quotes',tokens[tier]['member'],{'storeId':'store1','items':[{'skuId':'sku1','quantity':1}]})
with f.ThreadPoolExecutor(max_workers=12) as p: rows=list(p.map(mixed,range(180)))
times=sorted(r[1] for r in rows);perc=lambda x:times[round((len(times)-1)*x)]
print(f'three_tenants,180,{perc(.5):.2f},{perc(.95):.2f},{perc(.99):.2f},{180/(time.perf_counter()-start):.1f},180,mixed')
