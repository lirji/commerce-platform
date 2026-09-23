import { Alert, Button, Card, Form, Input, Space, Table, Tabs } from "antd";
import { LifecycleEffects } from "./LifecycleEffects";
import { useState } from "react";
import { encode, useCommand, useResource } from "../shared/api";
import { ErrorNotice, Fields, PageHead, initialDate, instant, money, time } from "../shared/ui";
type Series={seriesId:string;campaignId?:string;campaignVersion?:number;orders:number;paidOrders:number;received:string;refunded:string;netReceipts:string;discountGranted:string;platformFunding:string;merchantFunding:string;updatedAt:string};
type Report={rows:Series[];cohort:string;coverage:string;costBasis:string};
type Journey={journeyId:string;enrolled:number;completed:number;notified:number;entrySuppressed:number;notificationSuppressed:number};
export function MarketingEffects({store}:{store:string}){
 const [range,setRange]=useState({from:instant(initialDate(-86400*30)),to:instant(initialDate(86400))});
 const [after,setAfter]=useState("");const [journeyAfter,setJourneyAfter]=useState("");const [cursor,setCursor]=useState("");const [rebuildDone,setRebuildDone]=useState(false);
 const query=`storeId=${encode(store)}&from=${encode(range.from)}&to=${encode(range.to)}`;
 const report=useResource<Report>(store?`/admin/marketing-effects?${query}&after=${encode(after)}`:null);
 const journeys=useResource<Journey[]>(store?`/admin/journey-effects?${query}&after=${encode(journeyAfter)}`:null);
 const command=useCommand();const refresh=()=>{report.refresh();journeys.refresh();};
 if(!store)return <Alert type="info" title="请选择分析门店"/>;
 return <>
  <PageHead title="营销与旅程效果" description="用成交快照和成功退款核对活动版本，先看清经营结果，再调整下一轮配置。" extra={<Button onClick={refresh}>刷新结果</Button>}/>
  <Card style={{marginBottom:16}}><Form layout="inline" initialValues={{from:initialDate(-86400*30),to:initialDate(86400)}} onFinish={v=>{setRange({from:instant(v.from),to:instant(v.to)});setAfter("");setJourneyAfter("");}}>
   <Fields fields={[{name:"from",label:"下单 / 入组 / 批次起点",type:"datetime"},{name:"to",label:"选择终点（不含）",type:"datetime"}]}/><Button htmlType="submit">查询（最多93天）</Button>
  </Form></Card>
  <ErrorNotice error={report.error}/><ErrorNotice error={journeys.error}/>
  <Tabs items={[
   {key:"campaigns",label:"活动成交与退款",children:<Card>
    {report.data&&<Alert type="info" title={report.data.cohort} description={`${report.data.coverage}。${report.data.costBasis}`} style={{marginBottom:16}}/>}
    <Table<Series> rowKey="seriesId" dataSource={report.data?.rows} loading={report.loading} pagination={false} scroll={{x:1400}} columns={[
     {title:"活动 / 版本",render:(_,r)=>r.campaignId?`${r.campaignId} / v${r.campaignVersion}`:"无活动"},{title:"下单数",dataIndex:"orders"},{title:"成交数",dataIndex:"paidOrders"},
     {title:"成交实付",dataIndex:"received",render:money},{title:"成功退款",dataIndex:"refunded",render:money},{title:"净收入",dataIndex:"netReceipts",render:money},
     {title:"成交优惠",dataIndex:"discountGranted",render:money},{title:"平台承担",dataIndex:"platformFunding",render:money},{title:"商家承担",dataIndex:"merchantFunding",render:money},{title:"最后核对",dataIndex:"updatedAt",render:time}
    ]}/><Space><Button onClick={()=>setAfter("")} disabled={!after}>回到首页</Button><Button disabled={report.data?.rows.length!==50} onClick={()=>setAfter(report.data!.rows.at(-1)!.seriesId)}>下一页</Button></Space>
   </Card>},
   {key:"comparisons",label:"会员营销比较",children:<LifecycleEffects key={query} query={query}/>},
   {key:"journeys",label:"旅程执行",children:<Card>
    <Alert type="info" title="此页汇总各版本新增的执行事实，升级前记录不追溯补计。时间按执行发生时间；入组、完成和触达次数不代表带来了相应成交。"/>
    <Table<Journey> rowKey="journeyId" dataSource={journeys.data} pagination={false} loading={journeys.loading} columns={[
     {title:"旅程",dataIndex:"journeyId"},{title:"入组",dataIndex:"enrolled"},{title:"完成",dataIndex:"completed"},{title:"站内通知",dataIndex:"notified"},{title:"入组频控抑制",dataIndex:"entrySuppressed"},{title:"通知频控抑制",dataIndex:"notificationSuppressed"}
    ]}/><Space><Button onClick={()=>setJourneyAfter("")} disabled={!journeyAfter}>回到首页</Button><Button disabled={journeys.data?.length!==50} onClick={()=>setJourneyAfter(journeys.data!.at(-1)!.journeyId)}>下一页</Button></Space>
   </Card>},
   {key:"coverage",label:"补齐历史订单",children:<Card>
    <p>按本租户订单游标补建分析投影，每批最多100单。可复制游标供下次继续，也可留空从头安全重跑。</p>
    <ErrorNotice error={command.error}/>
    <Space wrap><Input aria-label="历史补齐游标" value={cursor} onChange={e=>{setCursor(e.target.value);setRebuildDone(false);}} placeholder="留空从头开始" style={{width:360}}/>
     <Button loading={command.busy} disabled={rebuildDone} onClick={async()=>{const result=await command.run<{processed:number;nextAfter:string;hasMore:boolean}>("/admin/marketing-effects/rebuild",{after:cursor,limit:100});if(result){setCursor(result.nextAfter);setRebuildDone(!result.hasMore);refresh();}}}>补齐下一批</Button>
     <Button onClick={()=>{setCursor("");setRebuildDone(false);}}>回到起点</Button></Space>
    {rebuildDone&&<Alert type="success" title="本轮历史订单已扫描到末尾" style={{marginTop:16}}/>}
   </Card>}
  ]}/>
 </>;
}
