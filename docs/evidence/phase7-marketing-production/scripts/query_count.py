#!/usr/bin/env python3
"""Real HTTP query-count probe via normalized MySQL digests in the isolated benchmark DB."""
import sys
sys.path.insert(0,'docs/evidence/phase7-marketing-production/scripts')
# Share the read-only/bootstrap helpers without executing the workload entry point.
from pathlib import Path
ns={}
script=Path('docs/evidence/phase7-marketing-production/scripts/contention_load.py').read_text()
exec(script.split('print("provider,stage,')[0],ns)
post,sql,request=ns['post'],ns['sql'],ns['request'];admin=ns['ADMIN'];when=ns['when']
import json,uuid
from urllib.request import Request,urlopen
tokens=json.loads(Path('.local/phase7-bench/tokens.json').read_text())
id='nplus-'+uuid.uuid4().hex[:8]
post('/v1/admin/stores',{'storeId':id,'merchantId':'merchant1','name':id})
post('/v1/admin/skus',{'skuId':id,'storeId':id,'title':id,'unitPrice':'25.00'})
leaf=lambda n:{'kind':'COMPARE','field':'orderAmount','operator':'GTE','valueType':'DECIMAL','value':str(n)}
rule={'kind':'ALL','children':[{'kind':'ALL','children':[leaf(n*16+m) for m in range(16)]} for n in range(4)]}
for n in range(20):
 ref=id+'-'+str(n)
 post('/v1/admin/audiences',{'audienceId':ref,'version':1,'name':ref,'source':'querycount','watermark':when(-1),'validUntil':when(3600),'memberIds':['m000010']})
 post('/v1/admin/campaigns',{'campaignId':ref,'version':1,'storeId':id,'name':ref,'validFrom':when(-60),'validTo':when(1800),'minimumSpend':'20.00','discountAmount':'1.00','rule':rule,'policy':{'audience':{'id':ref,'version':1}}})
 for v,action in enumerate(['submit','approve','publish']):post('/v1/admin/campaigns/'+ref+'/1/'+action,{'expectedVersion':v})
def snapshot():
 data=sql("SELECT DIGEST,COUNT_STAR,DIGEST_TEXT FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME='commerce_phase7_bench' AND DIGEST_TEXT LIKE 'SELECT%'")
 return {row.split('\t',2)[0]:(int(row.split('\t',2)[1]),row.split('\t',2)[2]) for row in data.splitlines()}
store=sql("SELECT MAX(store_id) FROM benefit_coupon_definition WHERE tenant_id='phase7-small'")
print('scenario,quotes,total_selects,candidate_queries,audience_queries')
for name,tier,s,sku in [('10_candidates','small','store1','sku1'),('81_candidates','large','store1','sku1'),('1_audience','small',store,store),('20_audiences_69_nodes','small',id,id)]:
 before=snapshot()
 for n in range(20):
  status,view,_=request('/v1/quotes',tokens[tier]['member'],{'storeId':s,'items':[{'skuId':sku,'quantity':1}]})
  assert status==200,(status,view)
 after=snapshot();total=candidates=audience=0
 for digest,(count,text) in after.items():
  delta=count-before.get(digest,(0,''))[0]
  if 'performance_schema' in text.lower() or 'version_comment' in text.lower():continue
  total+=delta
  if 'FROM `marketing_campaign`' in text and "STATUS =" in text:candidates+=delta
  if 'FROM `marketing_audience_snapshot`' in text and 'JOIN' in text:audience+=delta
 print(f'{name},20,{total},{candidates},{audience}')
 assert candidates==20 and audience in (0,20)
