import { Alert, Button, Card, Drawer, Space, Table, Tabs } from "antd";
import { useState } from "react";
import { encode, useResource } from "../shared/api";
import { CommandModal, ErrorNotice, PageHead, Status, money, time, type Field } from "../shared/ui";

type Product={productId:string;storeId:string;title:string;category:string;brand:string;version:number};
type Sku={skuId:string;storeId:string;title:string;unitPrice:string;revision:number;status:string;productId?:string;specifications:{name:string;value:string}[]};
type Revision={revision:number;title:string;unitPrice:string;status:string;reason:string;actorId:string;createdAt:string};
const metadata:Field[]=[{name:"title",label:"商品名称"},{name:"category",label:"分类"},{name:"brand",label:"品牌"}];
const skuFields:Field[]=[{name:"title",label:"销售名称"},{name:"unitPrice",label:"售价（元）",type:"money"}];

/** 操作入口相同，但每次请求由服务端重新判断实际门店授权。 */
export function ProductOperations({store}:{store:string}) {
 const [productAfter,setProductAfter]=useState("");const [skuAfter,setSkuAfter]=useState("");
 const [selected,setSelected]=useState<Sku>();const [historyAfter,setHistoryAfter]=useState(0);
 const products=useResource<Product[]>(store?`/operations/products?storeId=${encode(store)}&after=${encode(productAfter)}`:null);
 const skus=useResource<Sku[]>(store?`/operations/skus?storeId=${encode(store)}&after=${encode(skuAfter)}`:null);
 const history=useResource<Revision[]>(selected?`/operations/skus/${encode(selected.skuId)}/history?storeId=${encode(store)}&after=${historyAfter}`:null);
 const refresh=()=>{products.refresh();skus.refresh();history.refresh();};
 if(!store)return <Alert type="info" title="请先选择有经营权限的门店"/>;
 return <>
  <PageHead title="商品经营" description="商品、规格、价格与上下架。历史报价保留原价格，修订需填写原因。"/>
  <ErrorNotice error={products.error}/><ErrorNotice error={skus.error}/>
  <Tabs items={[
   {key:"sku",label:"销售规格与上下架",children:<Card>
    <Space wrap>
     <CommandModal title="创建销售规格" path="/operations/skus" fields={[
      {name:"skuId",label:"SKU标识"},{name:"productId",label:"所属商品标识（SPU）"},...skuFields,
      {name:"specifications",label:"规格组合",type:"textarea",help:"每行填写 属性=值，最多8项。例如 颜色=红色；无规格商品填写 款式=标准。"}]}
      build={v=>({...v,storeId:store,specifications:String(v.specifications).split("\n").filter(v=>v.trim()).map(line=>{
       const at=line.indexOf("=");if(at<1||!line.slice(at+1).trim())throw new Error("规格请使用 属性=值 格式");
       return {name:line.slice(0,at).trim(),value:line.slice(at+1).trim()};})})} onDone={refresh}/>
     <Button onClick={refresh}>刷新</Button>
    </Space>
    <Table<Sku> rowKey="skuId" dataSource={skus.data} loading={skus.loading} pagination={false} scroll={{x:900}} columns={[
     {title:"商品",dataIndex:"title"},{title:"SKU / SPU",render:(_,r)=><>{r.skuId}<br/>{r.productId??"历史商品"}</>},
     {title:"规格",render:(_,r)=>r.specifications.map(s=>`${s.name}：${s.value}`).join(" / ")||"—"},
     {title:"售价",dataIndex:"unitPrice",render:money},{title:"状态",dataIndex:"status",render:v=><Status value={v}/>},{title:"版本",dataIndex:"revision"},
     {title:"操作",render:(_,r)=><Space wrap>
      <CommandModal title="调价 / 上下架" path={"/operations/skus/"+encode(r.skuId)} buttonType="link"
       fields={[...skuFields,{name:"status",label:"销售状态",type:"select",options:[{label:"上架",value:"ACTIVE"},{label:"下架",value:"FROZEN"}]},{name:"reason",label:"修订原因"}]}
       initialValues={{title:r.title,unitPrice:r.unitPrice,status:r.status}} build={v=>({...v,storeId:store,expectedVersion:r.revision})} onDone={refresh}/>
      <Button type="link" onClick={()=>{setHistoryAfter(0);setSelected(r);}}>修订记录</Button>
     </Space>}
    ]}/>
    <Space><Button disabled={!skuAfter} onClick={()=>setSkuAfter("")}>回到首页</Button><Button disabled={skus.data?.length!==50} onClick={()=>setSkuAfter(skus.data!.at(-1)!.skuId)}>下一页</Button></Space>
   </Card>},
   {key:"product",label:"商品资料（SPU）",children:<Card>
    <CommandModal title="创建商品" path="/operations/products" fields={[{name:"productId",label:"商品标识"},...metadata]} build={v=>({...v,storeId:store})} onDone={refresh}/>
    <Table<Product> rowKey="productId" dataSource={products.data} loading={products.loading} pagination={false} columns={[
     {title:"标识",dataIndex:"productId"},{title:"名称",dataIndex:"title"},{title:"分类",dataIndex:"category"},{title:"品牌",dataIndex:"brand"},
     {title:"操作",render:(_,r)=><CommandModal title="编辑资料" path={"/operations/products/"+encode(r.productId)} buttonType="link" fields={metadata} initialValues={{title:r.title,category:r.category,brand:r.brand}} build={v=>({...v,storeId:store,expectedVersion:r.version})} onDone={refresh}/>}
    ]}/>
    <Space><Button disabled={!productAfter} onClick={()=>setProductAfter("")}>回到首页</Button><Button disabled={products.data?.length!==50} onClick={()=>setProductAfter(products.data!.at(-1)!.productId)}>下一页</Button></Space>
   </Card>}
  ]}/>
  <Drawer title={`${selected?.title??"商品"} · 修订记录`} open={!!selected} onClose={()=>setSelected(undefined)} size="large">
   <ErrorNotice error={history.error}/>
   <Table<Revision> rowKey="revision" dataSource={history.data} loading={history.loading} pagination={false} columns={[
    {title:"版本",dataIndex:"revision"},{title:"售价",dataIndex:"unitPrice",render:money},{title:"状态",dataIndex:"status",render:v=><Status value={v}/>},
    {title:"原因",dataIndex:"reason"},{title:"操作人",dataIndex:"actorId"},{title:"时间",dataIndex:"createdAt",render:time}]}/>
   <Space><Button onClick={()=>setHistoryAfter(0)}>最早记录</Button><Button disabled={history.data?.length!==50} onClick={()=>setHistoryAfter(history.data!.at(-1)!.revision)}>下一页</Button></Space>
  </Drawer>
 </>;
}
