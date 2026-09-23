#!/usr/bin/env python3
"""检查应用与前端同源交付；健康成功不冒充业务验收。"""
import json,os,re,urllib.request
base=os.getenv('COMMERCE_BASE_URL','http://127.0.0.1:8602')
with urllib.request.urlopen(base+'/actuator/health',timeout=5) as r:assert json.load(r)['status']=='UP'
with urllib.request.urlopen(base+'/',timeout=5) as r:html=r.read().decode()
asset=re.search(r'src="(/assets/[^\"]+\.js)"',html)
assert asset,'UI bundle was not packaged into the application'
with urllib.request.urlopen(base+asset.group(1),timeout=5) as r:assert r.status==200 and len(r.read())>1000
try:urllib.request.urlopen(base+'/v1/me',timeout=5);raise AssertionError('Unauthenticated API access was permitted')
except urllib.error.HTTPError as error:assert error.code==401
print(json.dumps({'baseUrl':base,'health':'UP','uiAsset':asset.group(1),'unauthenticatedApi':401}))
