import { Alert, Button, Card, Space, Table, Tabs } from "antd";
import { useState } from "react";
import { encode, useResource } from "../shared/api";
import { ErrorNotice, money, time } from "../shared/ui";
type JourneySeries={seriesId:string;journeyId:string;journeyVersion:number;observationDays:number;enrolledMembers:number;matureMembers:number;notified:number;benefitsGranted:number;couponsGranted:number;paidMembers:number;paidOrders:number;received:string;refunded:string;netReceipts:string;discountGranted:string;platformFunding:string;merchantFunding:string;updatedAt?:string};
type DeliverySeries={seriesId:string;batchId:string;issued:number;skipped:number;revoked:number;kept:number;paidOrders:number;received:string;refunded:string;netReceipts:string;couponDiscount:string;updatedAt?:string};
type Report<T>={rows:T[];basis:string;coverage:string;costBasis:string};
/** 描述性队列与实际用券两个口径分别分页，避免混加收入或编造ROI。 */
export function LifecycleEffects({query}:{query:string}){
 const [after,setAfter]=useState("");const [batchAfter,setBatchAfter]=useState("");
 const journeys=useResource<Report<JourneySeries>>(`/admin/marketing-effects/journeys?${query}&after=${encode(after)}`);
 const deliveries=useResource<Report<DeliverySeries>>(`/admin/marketing-effects/deliveries?${query}&after=${encode(batchAfter)}`);
 const basis=(r?:Report<unknown>)=>r?<Alert type="info" showIcon title={r.basis} description={`${r.coverage}。${r.costBasis}`} style={{marginBottom:16}}/>:null;
 return <Tabs items={[
  {key:"lifecycle",label:"旅程版本比较",children:<Card extra={<Button onClick={journeys.refresh}>刷新版本比较</Button>}>
   <ErrorNotice error={journeys.error}/>{basis(journeys.data)}
   <Table<JourneySeries> rowKey="seriesId" dataSource={journeys.data?.rows} loading={journeys.loading} pagination={false} scroll={{x:1900}} columns={[
    {title:"旅程 / 版本",fixed:"left",width:180,render:(_,r)=>`${r.journeyId} / v${r.journeyVersion}`},
    {title:"观察天数",dataIndex:"observationDays"},{title:"入组会员",dataIndex:"enrolledMembers"},{title:"已完整观察",dataIndex:"matureMembers"},
    {title:"付费会员",dataIndex:"paidMembers"},{title:"后续付费占比",render:(_,r)=>r.enrolledMembers?`${(r.paidMembers/r.enrolledMembers*100).toFixed(1)}%`:"—"},{title:"付费订单",dataIndex:"paidOrders"},
    {title:"站内触达",dataIndex:"notified"},{title:"权益 / 券",render:(_,r)=>`${r.benefitsGranted} / ${r.couponsGranted}`},
    {title:"现金收款",dataIndex:"received",render:money},{title:"成功退款",dataIndex:"refunded",render:money},{title:"净收款",dataIndex:"netReceipts",render:money},
    {title:"成交优惠",dataIndex:"discountGranted",render:money},{title:"平台承担",dataIndex:"platformFunding",render:money},{title:"商家承担",dataIndex:"merchantFunding",render:money},{title:"最后核对",dataIndex:"updatedAt",render:time}
   ]}/><Space><Button disabled={!after} onClick={()=>setAfter("")}>版本比较首页</Button><Button disabled={journeys.data?.rows.length!==50} onClick={()=>setAfter(journeys.data!.rows.at(-1)!.seriesId)}>下一页版本比较</Button></Space>
  </Card>},
  {key:"batches",label:"定向券批次比较",children:<Card extra={<Button onClick={deliveries.refresh}>刷新批次比较</Button>}>
   <ErrorNotice error={deliveries.error}/>{basis(deliveries.data)}
   <Table<DeliverySeries> rowKey="seriesId" dataSource={deliveries.data?.rows} loading={deliveries.loading} pagination={false} scroll={{x:1300}} columns={[
    {title:"发券批次",dataIndex:"batchId",fixed:"left",width:180},{title:"已发放",dataIndex:"issued"},{title:"已跳过",dataIndex:"skipped"},{title:"已撤销",dataIndex:"revoked"},{title:"撤销时保留",dataIndex:"kept"},
    {title:"用券付费订单",dataIndex:"paidOrders"},{title:"现金收款",dataIndex:"received",render:money},{title:"成功退款",dataIndex:"refunded",render:money},{title:"净收款",dataIndex:"netReceipts",render:money},{title:"券自身优惠",dataIndex:"couponDiscount",render:money},{title:"最后核对",dataIndex:"updatedAt",render:time}
   ]}/><Space><Button disabled={!batchAfter} onClick={()=>setBatchAfter("")}>批次比较首页</Button><Button disabled={deliveries.data?.rows.length!==50} onClick={()=>setBatchAfter(deliveries.data!.rows.at(-1)!.seriesId)}>下一页批次比较</Button></Space>
  </Card>}
 ]}/>;
}
