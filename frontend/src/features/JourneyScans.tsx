import { Alert, Button, Card, Form, Input, Modal, Space, Table } from "antd";
import { useState } from "react";
import { encode, useCommand, useResource } from "../shared/api";
import { ErrorNotice, Status, time } from "../shared/ui";
type Scan={journeyId:string;journeyVersion:number;status:string;memberCursor:string;createdBefore:string;nextDue:string;scanned:number;enrolled:number;attempts:number;errorCode?:string;version:number};
/** 扫描与实例分开展示，让运营能辨认未入组和等待中的会员。 */
export function JourneyScans(){
 const [after,setAfter]=useState("");const rows=useResource<Scan[]>(`/admin/journey-scans?after=${encode(after)}`);const command=useCommand();const [retry,setRetry]=useState<Scan>();
 return <Card title="生命周期扫描" style={{marginTop:20}} extra={<Space><Button onClick={rows.refresh}>刷新扫描</Button><Button loading={command.busy} onClick={async()=>{if(await command.run("/admin/journeys/pump")!==undefined)rows.refresh();}}>推进本租户旅程</Button></Space>}>
  <Alert type="info" showIcon title="发布后自动按周期检查会员" description="每轮有界处理，生日按 UTC；暂停版本停止新入组，关闭会员旅程偏好会停止后续执行。加购排除在加购后下单且已付款的同门店订单。" style={{marginBottom:16}}/>
  <ErrorNotice error={rows.error}/><ErrorNotice error={command.error}/>
  <Table<Scan> rowKey={r=>`${r.journeyId}/${r.journeyVersion}`} loading={rows.loading} dataSource={rows.data} pagination={false} scroll={{x:1000}} columns={[
   {title:"旅程 / 版本",render:(_,r)=>`${r.journeyId} / v${r.journeyVersion}`},{title:"扫描状态",dataIndex:"status",render:v=><Status value={v}/>},
   {title:"累计检查 / 入组",render:(_,r)=>`${r.scanned} / ${r.enrolled}`},{title:"会员游标",render:(_,r)=>r.memberCursor||"新一轮"},{title:"下一次检查",dataIndex:"nextDue",render:time},
   {title:"失败",render:(_,r)=>r.attempts?`${r.attempts} 次 · ${r.errorCode??""}`:"—"},{title:"操作",render:(_,r)=><Button size="small" disabled={r.status!=="ISOLATED"} onClick={()=>setRetry(r)}>恢复扫描</Button>}
  ]}/><Space><Button disabled={!after} onClick={()=>setAfter("")}>扫描首页</Button><Button disabled={rows.data?.length!==50} onClick={()=>setAfter(rows.data!.at(-1)!.journeyId)}>下一批扫描</Button></Space>
  <Modal title="恢复隔离扫描" open={!!retry} footer={null} destroyOnHidden onCancel={()=>setRetry(undefined)}><p>修复失败原因后从原会员游标继续，已完成入组会保留。</p><Form layout="vertical" onFinish={async v=>{if(retry&&await command.run(`/admin/journey-scans/${encode(retry.journeyId)}/${retry.journeyVersion}/retry`,{expectedVersion:retry.version,reason:v.reason})!==undefined){setRetry(undefined);rows.refresh();}}}><Form.Item name="reason" label="恢复原因" rules={[{required:true}]}><Input maxLength={256}/></Form.Item><Button type="primary" htmlType="submit" loading={command.busy}>确认恢复</Button></Form></Modal>
 </Card>;
}
