import { Alert, Button, Card, Empty, Segmented, Space, Spin, Table, Typography } from "antd";
import { useState } from "react";
import { encode, useResource } from "../shared/api";
import { ErrorNotice, PageHead, money, time } from "../shared/ui";
type Daily={day:string;orders:number;paidOrders:number;received:string;refunded:string;netReceipts:string;discountGranted:string;platformFunding:string;merchantFunding:string;updatedAt?:string};
type Summary={storeId:string;from:string;to:string;generatedAt:string;members:{total:number;active:number;frozen:number;closed:number};catalog:{total:number;active:number;frozen:number};daily:Daily[];totals:{paidOrders:number;received:string;refunded:string;netReceipts:string;discountGranted:string};coverage:string};
const shortcuts=[{page:"growth",label:"会员成长",text:"等级、周期权益与积分",mark:"会"},{page:"segments",label:"动态人群",text:"把真实行为转为经营对象",mark:"群"},{page:"journeys",label:"营销旅程",text:"生日、复购与流失关怀",mark:"旅"},{page:"skus",label:"商品管理",text:"规格、渠道价与经营计划",mark:"品"}];
/** 所有经营数字来自后端域聚合，空值和失败不会生成虚构趋势。 */
export function Dashboard({store,navigate}:{store:string;navigate:(page:string)=>void}){
 const data=useResource<Summary>(store?`/admin/dashboard?storeId=${encode(store)}`:null);const [metric,setMetric]=useState<"netReceipts"|"received"|"refunded">("netReceipts");const [table,setTable]=useState(false);
 if(!store)return <Alert type="info" title="选择门店查看经营总览"/>;
 const summary=data.data;const daily=summary?.daily??[];const sums=summary?.totals;
 const maximum=Math.max(1,...daily.map(d=>Number(d[metric])));const hasOrders=daily.some(d=>d.orders>0);
 return <div className="dashboard">
  <PageHead title="经营总览" description="把会员关系、商品经营与成交结果放在同一个视野。" extra={<Button onClick={data.refresh} loading={data.loading}>刷新总览</Button>}/>
  <ErrorNotice error={data.error}/><Spin spinning={data.loading}>
  {summary&&sums&&<>
   <div className="dashboard-intro"><span className="live-dot"/><span>当前门店 · 近30个UTC自然日</span><span className="dashboard-updated">读取于 {time(summary.generatedAt)}</span></div>
   <div className="metric-grid">
    {[{label:"会员总数",value:summary.members.total.toLocaleString(),note:`租户范围 · 可用 ${summary.members.active}`,tone:"blue"},{label:"在售规格",value:summary.catalog.active.toLocaleString(),note:`当前门店 · 全部 ${summary.catalog.total}`,tone:"teal"},{label:"已付订单",value:sums.paidOrders.toLocaleString(),note:"近30天 · 已投影订单",tone:"purple"},{label:"净收金额",value:money(sums.netReceipts),note:"实收减已知成功退款",tone:"amber"}].map(k=><Card key={k.label} className={`metric-card metric-${k.tone}`}><span className="metric-label">{k.label}</span><strong>{k.value}</strong><span className="metric-note">{k.note}</span></Card>)}
   </div>
   <div className="dashboard-main-grid">
    <Card className="trend-card" title="成交与退款趋势" extra={<Segmented aria-label="趋势指标" value={metric} onChange={v=>setMetric(v as typeof metric)} options={[{label:"净收",value:"netReceipts"},{label:"实收",value:"received"},{label:"退款",value:"refunded"}]}/>}>
     <div className="trend-summary"><span>实收 <b>{money(sums.received)}</b></span><span>成功退款 <b>{money(sums.refunded)}</b></span><span>成交优惠 <b>{money(sums.discountGranted)}</b></span></div>
     <div className="trend-plot" role="img" aria-label={`近30天${metric==="netReceipts"?"净收":metric==="received"?"实收":"退款"}趋势，单位人民币元`}>
      <span className="trend-max">{money(maximum.toFixed(2))}</span>
      <div className="trend-bars">{daily.map(d=><div key={d.day} className="trend-column" title={`${d.day} UTC · ${money(d[metric])}`}><div style={{height:`${Math.max(0,Number(d[metric]))/maximum*100}%`}}/></div>)}</div>
      {!hasOrders&&<div className="trend-empty"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="此窗口暂无已投影订单"/></div>}
     </div><div className="trend-axis"><span>{daily[0]?.day}</span><span>UTC 下单日期</span><span>{daily.at(-1)?.day}</span></div>
     <Button type="link" onClick={()=>setTable(!table)}>{table?"收起每日数据":"查看每日数据"}</Button>
     {table&&<Table<Daily> size="small" rowKey="day" dataSource={daily} pagination={{pageSize:7}} scroll={{x:680}} columns={[{title:"UTC日期",dataIndex:"day"},{title:"已付订单",dataIndex:"paidOrders"},{title:"实收",dataIndex:"received",render:money},{title:"退款",dataIndex:"refunded",render:money},{title:"净收",dataIndex:"netReceipts",render:money},{title:"优惠",dataIndex:"discountGranted",render:money}]}/>}
    </Card>
    <Card title="经营状态" className="state-card">
     <Typography.Text type="secondary">会员 · 当前租户</Typography.Text><div className="state-total">{summary.members.active}<small>可用会员</small></div>
     <div className="state-track"><span style={{width:`${summary.members.total?summary.members.active/summary.members.total*100:0}%`}}/></div>
     <div className="state-row"><span>已冻结</span><b>{summary.members.frozen}</b></div><div className="state-row"><span>已注销</span><b>{summary.members.closed}</b></div>
     <div className="state-divider"/><Typography.Text type="secondary">商品 · 当前门店</Typography.Text><div className="state-row"><span>在售规格</span><b>{summary.catalog.active}</b></div><div className="state-row"><span>下架规格</span><b>{summary.catalog.frozen}</b></div>
     <Button block onClick={()=>navigate("members")}>查看会员档案</Button>
    </Card>
   </div>
   <div className="shortcut-grid">{shortcuts.map(s=><button className="dashboard-shortcut" key={s.page} onClick={()=>navigate(s.page)}><span>{s.mark}</span><div><strong>{s.label}</strong><small>{s.text}</small></div><b aria-hidden="true">↗</b></button>)}</div>
   <Card size="small" className="dashboard-basis"><Typography.Text type="secondary">{summary.coverage} 成交优惠不扣减退款，不含货品、支付或渠道成本；净收不等于利润。</Typography.Text><Space wrap style={{marginTop:8}}><Button type="link" onClick={()=>navigate("effects")}>进入营销效果分析</Button><Button type="link" onClick={()=>navigate("events")}>查看事件处理</Button></Space></Card>
  </>}
  </Spin>
 </div>;
}
