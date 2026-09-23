import { Alert, Button, Card, Drawer, Input, Space, Table, Typography } from "antd";
import { useState } from "react";
import { encode, useCommand, useResource } from "../shared/api";
import { CommandModal, ErrorNotice, Status, initialDate, instant, localDateTime, money, time } from "../shared/ui";
export type JobSku={skuId:string;title:string;unitPrice:string;revision:number;status:string};
type Target={skuId:string;expectedRevision:number;unitPrice?:string};
type Job={definition:{jobId:string;name:string;action:string;runAt:string;deadline:string;targets:Target[]};status:string;processed:number;succeeded:number;conflicted:number;attempts:number;errorCode?:string;version:number;creatorId:string};
type Receipt={itemIndex:number;skuId:string;status:string;expectedRevision:number;actualRevision?:number;reason:string;processedAt:string};
type Price={channel:string;version:number;unitPrice:string;validFrom:string;validTo:string;active:boolean;reason:string;actorId:string;changedAt:string};
const actions:Record<string,string>={PRICE:"批量调价",PUBLISH:"批量上架",UNPUBLISH:"批量下架"};
/** 固定选择及版本预览，提交后由可恢复任务执行。 */
export function BatchCatalogAction({store,rows,onDone}:{store:string;rows:JobSku[];onDone:()=>void}){
 const [jobId,setJobId]=useState(()=>crypto.randomUUID());
 const [prices,setPrices]=useState<Record<string,string>>({});
 return <CommandModal title="创建批量计划" path="/operations/catalog-jobs" disabled={!rows.length} fields={[
  {name:"name",label:"计划名称"},{name:"action",label:"经营动作",type:"select",options:Object.entries(actions).map(([value,label])=>({value,label})),initial:"PRICE"},
  {name:"runAt",label:"计划开始时间",type:"datetime",required:false,help:"留空立即执行；定时计划最多提前30天。"},
  {name:"deadline",label:"执行截止时间",type:"datetime",initial:initialDate(86400),help:"开始后最多7天；截止后不再执行剩余项。"},
  {name:"reason",label:"经营原因"}
 ]} build={v=>({...v,jobId,storeId:store,runAt:v.runAt?instant(v.runAt):null,deadline:instant(v.deadline),targets:rows.map(r=>({skuId:r.skuId,expectedRevision:r.revision,...(v.action==="PRICE"?{unitPrice:prices[r.skuId]??r.unitPrice}:{})}))})} onDone={()=>{setJobId(crypto.randomUUID());setPrices({});onDone();}}>
  <Alert type="info" showIcon title={`已固定 ${rows.length} 个规格的预期版本`} description="执行时有版本冲突的项目会跳过并记录回执。上下架操作不使用下方目标价格。"/>
  <Table rowKey="skuId" dataSource={rows} pagination={false} size="small" scroll={{y:260}} columns={[{title:"规格 / 预期版本",render:(_,r)=><>{r.title}<br/><Typography.Text type="secondary">{r.skuId} · v{r.revision}</Typography.Text></>},{title:"目标价格（元）",render:(_,r)=><Input aria-label={`${r.title}目标价格`} value={prices[r.skuId]??r.unitPrice} onChange={e=>setPrices({...prices,[r.skuId]:e.target.value})}/>}]} />
 </CommandModal>;
}
/** 计划和逐项回执独立展示，不将受理误显示成调价成功。 */
export function CatalogJobs({store}:{store:string}){
 const [after,setAfter]=useState("");const [selected,setSelected]=useState<Job>();
 const jobs=useResource<Job[]>(`/operations/catalog-jobs?storeId=${encode(store)}&after=${encode(after)}`);
 const receipts=useResource<Receipt[]>(selected?`/operations/catalog-jobs/${encode(selected.definition.jobId)}/items?storeId=${encode(store)}`:null);const command=useCommand();
 const refresh=()=>{jobs.refresh();receipts.refresh();};
 return <Card title="批量与定时计划" extra={<Space><Button onClick={refresh}>刷新计划</Button><Button loading={command.busy} onClick={async()=>{if(await command.run(`/operations/catalog-jobs/pump?storeId=${encode(store)}`)!==undefined)refresh();}}>推进一批</Button></Space>}>
  <Alert type="info" title="从「销售规格与上下架」勾选商品后创建计划" description="任务保留创建人的经营权限边界。撤权或临时失败会退避重试，连续5次失败后隔离；取消仅停止尚未执行的项目。"/>
  <ErrorNotice error={jobs.error??command.error}/>
  <Table<Job> rowKey={r=>r.definition.jobId} dataSource={jobs.data} pagination={false} loading={jobs.loading} scroll={{x:1050}} columns={[
   {title:"计划",render:(_,r)=><>{r.definition.name}<br/><Typography.Text type="secondary">{actions[r.definition.action]}</Typography.Text></>},{title:"开始 / 截止",render:(_,r)=><>{time(r.definition.runAt)}<br/>{time(r.definition.deadline)}</>},
   {title:"状态",render:(_,r)=><><Status value={r.status}/>{r.errorCode&&<div>{r.errorCode} · 重试 {r.attempts}/5</div>}</>},{title:"执行进度",render:(_,r)=>`${r.processed}/${r.definition.targets.length} · 成功 ${r.succeeded} · 冲突 ${r.conflicted}`},
   {title:"操作",render:(_,r)=><Space wrap><Button type="link" onClick={()=>setSelected(r)}>逐项回执</Button>{["SCHEDULED","RUNNING","ISOLATED"].includes(r.status)&&<CommandModal title="取消计划" buttonType="link" path={`/operations/catalog-jobs/${encode(r.definition.jobId)}/control`} fields={[{name:"reason",label:"取消原因"}]} build={v=>({...v,storeId:store,expectedVersion:r.version,action:"CANCEL"})} onDone={refresh}/>} {r.status==="ISOLATED"&&<CommandModal title="恢复计划" buttonType="link" path={`/operations/catalog-jobs/${encode(r.definition.jobId)}/control`} fields={[{name:"reason",label:"恢复原因"}]} build={v=>({...v,storeId:store,expectedVersion:r.version,action:"RETRY"})} onDone={refresh}/>}</Space>}
  ]}/>
  <Space><Button disabled={!after} onClick={()=>setAfter("")}>计划首页</Button><Button disabled={jobs.data?.length!==50} onClick={()=>setAfter(jobs.data!.at(-1)!.definition.jobId)}>下一页计划</Button></Space>
  <Drawer title={`${selected?.definition.name??"计划"} · 逐项回执`} open={!!selected} onClose={()=>setSelected(undefined)} size="large"><ErrorNotice error={receipts.error}/><Button onClick={receipts.refresh}>刷新回执</Button><Table<Receipt> rowKey="itemIndex" dataSource={receipts.data} loading={receipts.loading} pagination={false} columns={[{title:"规格",dataIndex:"skuId"},{title:"结果",dataIndex:"status",render:v=><Status value={v}/>},{title:"预期 / 实际版本",render:(_,r)=>`${r.expectedRevision} / ${r.actualRevision??"缺失"}`},{title:"原因",dataIndex:"reason",render:v=>({APPLIED:"已执行",REVISION_CHANGED:"商品已被修改",SKU_MISSING:"商品不存在"})[v as string]??v},{title:"执行时间",dataIndex:"processedAt",render:time}]}/></Drawer>
 </Card>;
}
/** 渠道价管理与基础售价分开，显示有效窗口和不可变历史。 */
export function ChannelPrices({store,sku,onClose}:{store:string;sku?:JobSku;onClose:()=>void}){
 const [channel,setChannel]=useState<string>();const prices=useResource<Price[]>(sku?`/operations/skus/${encode(sku.skuId)}/channel-prices?storeId=${encode(store)}`:null);
 const history=useResource<Price[]>(sku&&channel?`/operations/skus/${encode(sku.skuId)}/channel-prices/${channel}/history?storeId=${encode(store)}`:null);
 return <Drawer title={`${sku?.title??"商品"} · 渠道价格`} open={!!sku} onClose={()=>{setChannel(undefined);onClose();}} size="large">
  <Alert type="info" title="未配置、停用或有效期外，自动使用基础售价" description="渠道由登录凭据确定；新价格不会重写已生成且仍有效的报价。"/><ErrorNotice error={prices.error}/>
  {sku&&["WEB","MINI_APP"].map(c=>{const row=prices.data?.find(p=>p.channel===c);return <Card key={c} size="small" title={c==="WEB"?"网站渠道":"小程序渠道"} style={{marginTop:16}}><Space direction="vertical"><Typography.Text>{row?`${money(row.unitPrice)} · v${row.version} · ${row.active?"已启用":"已停用"}`:"使用基础价"}</Typography.Text>{row&&<Typography.Text type="secondary">{time(row.validFrom)} — {time(row.validTo)}</Typography.Text>}
   <Space><CommandModal key={`${sku.skuId}-${c}-${row?.version??0}`} title={c==="WEB"?"设置网站价":"设置小程序价"} path={`/operations/skus/${encode(sku.skuId)}/channel-prices`} disabled={prices.loading||!!prices.error} fields={[{name:"unitPrice",label:"渠道售价（元）",type:"money"},{name:"validFrom",label:"生效时间",type:"datetime"},{name:"validTo",label:"失效时间",type:"datetime"},{name:"active",label:"启用价格",type:"switch",required:false},{name:"reason",label:"变更原因"}]} initialValues={{unitPrice:row?.unitPrice??sku.unitPrice,validFrom:row?localDateTime(row.validFrom):initialDate(0),validTo:row?localDateTime(row.validTo):initialDate(86400*7),active:row?.active??true}} build={v=>({...v,storeId:store,channel:c,expectedVersion:row?.version??0,validFrom:instant(v.validFrom),validTo:instant(v.validTo)})} onDone={()=>{prices.refresh();history.refresh();}}/><Button onClick={()=>setChannel(c)}>查看{c==="WEB"?"网站":"小程序"}价历史</Button></Space>
  </Space></Card>;})}
  {channel&&<><Typography.Title level={4}>渠道价历史</Typography.Title><ErrorNotice error={history.error}/><Table<Price> rowKey="version" dataSource={history.data} pagination={false} loading={history.loading} columns={[{title:"版本",dataIndex:"version"},{title:"价格",dataIndex:"unitPrice",render:money},{title:"有效期",render:(_,r)=><>{time(r.validFrom)}<br/>{time(r.validTo)}</>},{title:"原因",dataIndex:"reason"},{title:"操作人",dataIndex:"actorId"}]}/></>}
 </Drawer>;
}
