import { Alert, Button, Card, Descriptions, Drawer, Form, Input, InputNumber, Select, Space, Table, Tabs } from "antd";
import { useEffect, useState } from "react";
import { encode, useCommand, useResource } from "../shared/api";
import { CommandModal, ErrorNotice, Fields, Status, type Values } from "../shared/ui";
export type Category={categoryId:string;storeId:string;parentId?:string;name:string;depth:number;status:string;version:number};
export type Template={templateId:string;version:number;storeId:string;name:string;fields:{name:string;values:string[]}[]};
export type ProductProfile={productId:string;storeId:string;categoryId?:string;templateId?:string;templateVersion?:number;description:string;images:{url:string;alt:string}[];version:number};
export type CatalogItem={skuId:string;storeId:string;title:string;unitPrice:string;revision:number;status:string;productId?:string;categoryId?:string;categoryName?:string;barcode?:string;description:string;images:{url:string;alt:string}[];specifications:{name:string;value:string}[]};
function attributes(value:unknown){return String(value).split("\n").filter(v=>v.trim()).map(line=>{const at=line.indexOf("=");if(at<1)throw new Error("请按 属性=允许值1,允许值2 填写模板");return {name:line.slice(0,at).trim(),values:line.slice(at+1).split(/[,，]/).map(v=>v.trim())};});}
/** 类目与模板使用真实可分页目录，创建新版本不会修改旧绑定。 */
export function CatalogStructure({store}:{store:string}){
 const [categoryAfter,setCategoryAfter]=useState("");const [templateAfter,setTemplateAfter]=useState("");
 const categories=useResource<Category[]>(`/operations/catalog-categories?storeId=${encode(store)}&after=${encode(categoryAfter)}`);
 const templates=useResource<Template[]>(`/operations/specification-templates?storeId=${encode(store)}&after=${encode(templateAfter)}`);
 return <Tabs items={[
  {key:"categories",label:"类目目录",children:<Card><ErrorNotice error={categories.error}/><Space>
   <CommandModal title="创建类目" path="/operations/catalog-categories" fields={[{name:"categoryId",label:"类目标识"},{name:"name",label:"类目名称"},{name:"parentId",label:"父类目标识",required:false,help:"留空为根类目，最多三级，父级创建后不可更换"}]} build={v=>({...v,storeId:store,parentId:v.parentId||null})} onDone={categories.refresh}/><Button onClick={categories.refresh}>刷新类目</Button></Space>
   <Table<Category> rowKey="categoryId" dataSource={categories.data} loading={categories.loading} pagination={false} columns={[
    {title:"类目",render:(_,r)=><span style={{paddingLeft:(r.depth-1)*16}}>{r.name}</span>},{title:"标识",dataIndex:"categoryId"},{title:"父级",render:(_,r)=>r.parentId??"根类目"},{title:"层级",dataIndex:"depth"},{title:"状态",dataIndex:"status",render:v=><Status value={v}/>},
    {title:"操作",render:(_,r)=><CommandModal title="维护类目" buttonType="link" path={`/operations/catalog-categories/${encode(r.categoryId)}`} fields={[{name:"name",label:"类目名称"},{name:"status",label:"类目状态",type:"select",options:[{value:"ACTIVE",label:"启用"},{value:"RETIRED",label:"停用"}]},{name:"reason",label:"类目变更原因"}]} initialValues={{name:r.name,status:r.status}} build={v=>({...v,storeId:store,expectedVersion:r.version})} onDone={categories.refresh}/>}
   ]}/><Space><Button disabled={!categoryAfter} onClick={()=>setCategoryAfter("")}>类目首页</Button><Button disabled={categories.data?.length!==100} onClick={()=>setCategoryAfter(categories.data!.at(-1)!.categoryId)}>下一批类目</Button></Space>
  </Card>},
  {key:"templates",label:"规格模板",children:<Card><ErrorNotice error={templates.error}/><Alert type="info" title="模板按版本固定，先绑定商品再创建规格" description="已绑定商品不会跟随新版本。已有自由规格商品可继续经营；需要更换模板时创建新商品。" style={{marginBottom:16}}/>
   <CommandModal title="创建规格模板" path="/operations/specification-templates" fields={[{name:"templateId",label:"模板标识"},{name:"version",label:"模板版本",type:"number",min:1},{name:"name",label:"模板名称"},{name:"fields",label:"属性与允许值",type:"textarea",help:"每行 属性=值1,值2，例如 颜色=红色,蓝色；最多8个属性、每项最多50个值"}]} initialValues={{version:1}} build={v=>({...v,storeId:store,fields:attributes(v.fields)})} onDone={templates.refresh}/>
   <Table<Template> rowKey="templateId" dataSource={templates.data} loading={templates.loading} pagination={false} columns={[{title:"模板",dataIndex:"name"},{title:"标识",dataIndex:"templateId"},{title:"最新版本",dataIndex:"version"},{title:"允许组合",render:(_,r)=>r.fields.map(f=>`${f.name}：${f.values.join(" / ")}`).join("；")}]}/>
   <Space><Button disabled={!templateAfter} onClick={()=>setTemplateAfter("")}>模板首页</Button><Button disabled={templates.data?.length!==100} onClick={()=>setTemplateAfter(templates.data!.at(-1)!.templateId)}>下一批模板</Button></Space>
  </Card>}
 ]}/>;
}
export function ProductPresentation({store,product,onClose}:{store:string;product?:{productId:string;title:string};onClose:()=>void}){
 const profile=useResource<ProductProfile>(product?`/operations/products/${encode(product.productId)}/merchandising?storeId=${encode(store)}`:null);
 return <Drawer title={`${product?.title??"商品"} · 展示资料`} open={!!product} onClose={onClose} size="large"><ErrorNotice error={profile.error}/>{product&&profile.data&&<>
  <Descriptions column={1} items={[{key:"category",label:"结构化类目",children:profile.data.categoryId??"未绑定"},{key:"template",label:"规格模板",children:profile.data.templateId?`${profile.data.templateId} / v${profile.data.templateVersion}`:"自由规格"},{key:"description",label:"商品说明",children:<span style={{whiteSpace:"pre-wrap"}}>{profile.data.description||"尚未补充"}</span>},{key:"version",label:"资料版本",children:profile.data.version}]}/>
  <div className="catalog-gallery">{profile.data.images.map(image=><img key={image.url} src={image.url} alt={image.alt} referrerPolicy="no-referrer" loading="lazy"/>)}</div>
  <CommandModal key={profile.data.version} title="编辑展示资料" path={`/operations/products/${encode(product!.productId)}/merchandising`} fields={[
   {name:"categoryId",label:"绑定类目标识",required:false},{name:"templateId",label:"绑定模板标识",required:false,help:"仅无SKU商品可首次绑定，绑定后不可更换"},{name:"templateVersion",label:"绑定模板版本",type:"number",min:1,required:false},
   {name:"description",label:"商品详细说明",type:"textarea",required:false},{name:"pictures",label:"商品图片",type:"textarea",required:false,help:"每行 图片地址|图片说明，最多6张，HTTPS或站内/media路径"},{name:"reason",label:"展示资料变更原因"}
  ]} initialValues={{...profile.data,pictures:profile.data.images.map(p=>`${p.url}|${p.alt}`).join("\n")}} build={v=>({storeId:store,expectedVersion:profile.data!.version,categoryId:v.categoryId||null,templateId:v.templateId||null,templateVersion:v.templateId?v.templateVersion:null,description:v.description??"",images:String(v.pictures??"").split("\n").filter(line=>line.trim()).map(line=>{const at=line.lastIndexOf("|");if(at<1)throw new Error("图片请按 地址|说明 填写");return {url:line.slice(0,at).trim(),alt:line.slice(at+1).trim()};}),reason:v.reason})} onDone={profile.refresh}/>
 </>}</Drawer>;
}
export function BarcodeEditor({store,sku,onClose,onDone}:{store:string;sku?:{skuId:string;title:string};onClose:()=>void;onDone:()=>void}){
 const barcode=useResource<{barcode?:string;version:number}>(sku?`/operations/skus/${encode(sku.skuId)}/barcode?storeId=${encode(store)}`:null);
 return <Drawer title={`${sku?.title??"规格"} · 条码资料`} open={!!sku} onClose={onClose} size="default"><ErrorNotice error={barcode.error}/>{sku&&barcode.data&&<><p>当前条码：{barcode.data.barcode??"未设置"}</p><p className="muted">门店内唯一，字母统一为大写；清空后可供其他规格使用。不会改变销售价格或报价快照。</p><CommandModal key={barcode.data.version} title="维护条码" path={`/operations/skus/${encode(sku!.skuId)}/barcode`} fields={[{name:"barcode",label:"经营条码",required:false},{name:"reason",label:"条码变更原因"}]} initialValues={{barcode:barcode.data.barcode}} build={v=>({...v,storeId:store,barcode:v.barcode??null,expectedVersion:barcode.data!.version})} onDone={()=>{barcode.refresh();onDone();}}/></>}</Drawer>;
}
/** 绑定模板后只展示其允许值；自由规格保留原输入方式。 */
function VariantForm({store,onDone}:{store:string;onDone:()=>void}){
 const [form]=Form.useForm();const command=useCommand();const productId=Form.useWatch<string>("productId",form);
 useEffect(()=>{form.setFieldValue("choices",[]);},[form,productId]);
 const profile=useResource<ProductProfile>(productId?`/operations/products/${encode(productId)}/merchandising?storeId=${encode(store)}`:null);
 const template=useResource<Template>(profile.data?.templateId?`/operations/specification-templates/${encode(profile.data.templateId)}/${profile.data.templateVersion}?storeId=${encode(store)}`:null);
 const finish=async(v:Values)=>{const specifications=template.data?template.data.fields.map((f,i)=>({name:f.name,value:(v.choices as string[])?.[i]})):String(v.specifications??"").split("\n").filter(v=>v.trim()).map(line=>{const at=line.indexOf("=");if(at<1)throw new Error("规格请用 属性=值 格式");return {name:line.slice(0,at).trim(),value:line.slice(at+1).trim()};});return command.run("/operations/skus",{skuId:v.skuId,productId:v.productId,storeId:store,title:v.title,unitPrice:v.unitPrice,specifications});};
 const [localError,setLocalError]=useState<Error>();
 return <Form form={form} layout="vertical" onFinish={async v=>{try{setLocalError(undefined);if(await finish(v)!==undefined)onDone();}catch(e){setLocalError(e instanceof Error?e:new Error("规格格式无效"));}}}>
  <ErrorNotice error={command.error??localError??profile.error??template.error}/><Fields fields={[{name:"skuId",label:"SKU标识"},{name:"productId",label:"所属商品标识（SPU）"},{name:"title",label:"销售名称"},{name:"unitPrice",label:"售价（元）",type:"money"}]}/>
  {template.data?<><p className="muted">固定模板：{template.data.name} / v{template.data.version}</p>{template.data.fields.map((f,i)=><Form.Item key={`${productId}/${f.name}`} name={["choices",i]} label={`规格：${f.name}`} rules={[{required:true}]}><Select options={f.values.map(value=>({value,label:value}))}/></Form.Item>)}</>:!profile.loading&&!profile.data?.templateId?<Fields fields={[{name:"specifications",label:"规格组合",type:"textarea",help:"每行 属性=值，最多8项。"}]}/>:null}
  <Button type="primary" htmlType="submit" loading={command.busy} disabled={profile.loading||template.loading||!!profile.error||!!template.error||!profile.data}>保存销售规格</Button>
 </Form>;
}
export function VariantCreator({store,onDone}:{store:string;onDone:()=>void}){const [open,setOpen]=useState(false);return <><Button type="primary" onClick={()=>setOpen(true)}>创建销售规格</Button><Drawer title="创建销售规格" open={open} onClose={()=>setOpen(false)} destroyOnHidden size="large"><VariantForm store={store} onDone={()=>{setOpen(false);onDone();}}/></Drawer></>;}
export function CatalogFilters({categories,onSearch,operations=false}:{categories:Category[];onSearch:(query:string)=>void;operations?:boolean}){
 return <Form layout="inline" onFinish={v=>{const params=new URLSearchParams();for(const [key,value] of Object.entries(v))if(value!==undefined&&value!==null&&value!=="")params.set(key,String(value));onSearch(params.toString());}} className="catalog-filters">
  <Form.Item name="q"><Input aria-label="商品名称或条码" placeholder="商品名称 / 条码" allowClear maxLength={64}/></Form.Item>
  <Form.Item name="categoryId"><Select aria-label="筛选商品类目" placeholder="全部类目" allowClear style={{width:180}} options={categories.map(c=>({value:c.categoryId,label:c.name}))}/></Form.Item>
  <Form.Item name="minimumPrice"><InputNumber aria-label="最低售价" placeholder="最低价" min={0} precision={2}/></Form.Item><Form.Item name="maximumPrice"><InputNumber aria-label="最高售价" placeholder="最高价" min={0} precision={2}/></Form.Item>
  {operations&&<Form.Item name="status"><Select aria-label="筛选销售状态" placeholder="全部状态" allowClear style={{width:120}} options={[{value:"ACTIVE",label:"上架"},{value:"FROZEN",label:"下架"}]}/></Form.Item>}
  <Button htmlType="submit">筛选商品</Button>
 </Form>;
}
