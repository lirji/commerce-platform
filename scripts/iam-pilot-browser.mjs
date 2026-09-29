import {chromium} from '../frontend/node_modules/@playwright/test/index.mjs';
import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const run=process.env.P5_RUN, phase=process.env.P5_PHASE;
const fixture=JSON.parse(await fs.readFile(`${run}/pilot.json`,'utf8'));
const HTTP={OK:200,FORBIDDEN:403,CONFLICT:409};
const Phase={READ:'read-only',WRITE:'write',REVOKED:'revoked'};
const browser=await chromium.launch({headless:true});
const context=await browser.newContext({viewport:{width:1440,height:1000}});
await context.addInitScript(f=>sessionStorage.setItem(`oidc.user:${f.authority}:${f.client}`,JSON.stringify({access_token:f.token,token_type:'Bearer',scope:'openid profile',profile:{sub:'isolated-employee'},expires_at:Math.floor(Date.now()/1000)+1800})),fixture);
const page=await context.newPage();const checks=[];
function record(check){checks.push({check,result:'PASS'});}
async function call(path,body){return page.evaluate(async({path,body,fixture})=>{
 const r=await fetch('/v1/operations/scoped/product'+path,{method:body?'POST':'GET',headers:{Authorization:`Bearer ${fixture.token}`,'X-Tenant-Id':fixture.tenant,'Content-Type':'application/json','Idempotency-Key':crypto.randomUUID()},body:body?JSON.stringify(body):undefined});return {status:r.status,body:await r.json()};
},{path,body,fixture});}
try {
 await page.goto(`http://127.0.0.1:18605/operations/products?tenant_id=${fixture.tenant}`);
 await page.getByRole('button',{name:'查看资料',exact:true}).click();
 await page.getByText('归属门店',{exact:true}).last().waitFor();
 await page.waitForTimeout(350);
 if(phase!==Phase.WRITE){
  await page.getByText('当前可查看商品；编辑需要另行授权',{exact:true}).waitFor();
  assert.equal(await page.getByRole('button',{name:'编辑商品资料',exact:true}).count(),0);
  const denied=await call('/resources/P001',{expectedVersion:0,title:'forged',category:'C',brand:'B'});assert.equal(denied.status,HTTP.FORBIDDEN);
  assert.equal((await call('/resources/P002')).status,HTTP.FORBIDDEN);
  assert.equal((await call('/resources/FOREIGN')).status,HTTP.FORBIDDEN);
  record(`${phase}: authorized product remains readable; hidden edit and direct write denied; other store/tenant denied`);
 } else {
  await page.getByRole('button',{name:'编辑商品资料',exact:true}).click();
  await page.getByLabel('商品名称',{exact:true}).fill('P5 browser edited product');
  await page.screenshot({path:`${run}/internal-edit-1440.png`,fullPage:true});
  await page.getByRole('button',{name:'保存商品资料',exact:true}).click();
  await page.getByText('商品资料已保存',{exact:true}).waitFor();
  const current=await call('/resources/P001');assert.equal(current.status,HTTP.OK);assert.equal(current.body.title,'P5 browser edited product');assert.equal(current.body.resourceVersion,1);
  assert.equal((await call('/resources/P001',{expectedVersion:0,title:'stale',category:'C',brand:'B'})).status,HTTP.CONFLICT);
  assert.equal((await call('/resources/P002',{expectedVersion:0,title:'forged',category:'C',brand:'B'})).status,HTTP.FORBIDDEN);
  record('browser edits real authorized product; MySQL version advances; stale version and other store write denied');
 }
 await page.screenshot({path:`${run}/internal-${phase}-1440.png`,fullPage:true});
 await page.reload();await page.getByText('资料版本',{exact:true}).waitFor();
 if(phase===Phase.WRITE)await page.getByRole('button',{name:'编辑商品资料',exact:true}).waitFor();else await page.getByText('当前可查看商品；编辑需要另行授权',{exact:true}).waitFor();
 record('URL reload restores resource detail through fresh authorization');
 await page.setViewportSize({width:390,height:844});await page.screenshot({path:`${run}/internal-${phase}-390.png`,fullPage:true});
 await fs.writeFile(`${run}/internal-${phase}-result.json`,JSON.stringify(checks,null,2));
} catch(error){await page.screenshot({path:`${run}/internal-failed.png`,fullPage:true});throw error;}
finally {await browser.close();}
