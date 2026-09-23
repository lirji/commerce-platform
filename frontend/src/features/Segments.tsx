import { Alert, Button, Card, Drawer, Form, Modal, Space, Table } from "antd";
import { useState } from "react";
import type { Rule } from "../shared/contracts";
import { encode, useCommand, useResource } from "../shared/api";
import { ActionButton, ErrorNotice, Fields, PageHead, Status, time } from "../shared/ui";
import { RuleEditor } from "../shared/marketing";
type Definition={segmentId:string;version:number;name:string;rule:Rule;ttlSeconds:number;refreshSeconds:number;maxMembers:number};
type Segment={content:Definition;audienceId:string;enabled:boolean;lockVersion:number};
type Run={runId:string;snapshotVersion:number;definitionVersion:number;processed:number;matched:number;status:string;attempts:number;errorCode?:string;startedAt:string;validUntil:string;entriesAnnounced:boolean;entryAttempts:number};

/** 运营看到真实检查点；刷新任务与已发布受众版本在界面上明确区分。 */
export function Segments(){
 const [after,setAfter]=useState("");const [selected,setSelected]=useState<Segment>();const [runAfter,setRunAfter]=useState("");const [open,setOpen]=useState(false);
 const resource=useResource<Segment[]>(`/admin/segments?after=${encode(after)}`);
 const runs=useResource<Run[]>(selected?`/admin/segments/${encode(selected.content.segmentId)}/runs?after=${encode(runAfter)}`:null);
 const command=useCommand();const [form]=Form.useForm();
 const refresh=()=>{resource.refresh();runs.refresh();};
 return <>
  <PageHead title="动态人群" description="按可信会员事实分批刷新，完整结果才发布为可引用受众版本。" extra={<Space><Button onClick={refresh}>刷新列表</Button><Button type="primary" onClick={()=>{form.resetFields();command.clear();setOpen(true);}}>发布人群定义</Button><ActionButton label="执行一批刷新任务" path="/admin/segments/pump" onDone={refresh}/></Space>}/>
  <ErrorNotice error={resource.error}/>
  <Card><Table<Segment> rowKey={r=>r.content.segmentId} dataSource={resource.data} loading={resource.loading} pagination={false} scroll={{x:1050}} columns={[
   {title:"人群",render:(_,r)=><>{r.content.name}<br/>{r.content.segmentId}</>},{title:"定义版本",render:(_,r)=>r.content.version},
   {title:"周期",render:(_,r)=>r.enabled?`${r.content.refreshSeconds}秒` : "手工刷新"},
   {title:"快照有效期",render:(_,r)=>`${r.content.ttlSeconds}秒`},
   {title:"操作",render:(_,r)=><Space wrap>
    <ActionButton label="开始刷新" path={`/admin/segments/${encode(r.content.segmentId)}/refresh`} onDone={()=>{setSelected(r);setRunAfter("");refresh();}}/>
    <ActionButton label={r.enabled?"停止周期刷新":"启用周期刷新"} path={`/admin/segments/${encode(r.content.segmentId)}/schedule`} body={{expectedVersion:r.lockVersion,enabled:!r.enabled}} onDone={refresh} disabled={!r.enabled&&r.content.refreshSeconds===0}/>
    <Button onClick={()=>{setSelected(r);setRunAfter("");}}>任务与受众版本</Button>
    <Button onClick={()=>{form.setFieldsValue({...r.content,version:r.content.version+1});command.clear();setOpen(true);}}>复制为新定义</Button>
   </Space>}
  ]}/><Space><Button disabled={!after} onClick={()=>setAfter("")}>回到首页</Button><Button disabled={resource.data?.length!==50} onClick={()=>setAfter(resource.data!.at(-1)!.content.segmentId)}>下一页</Button></Space></Card>
  <Drawer title={`${selected?.content.name??"人群"} · 刷新任务`} open={!!selected} onClose={()=>setSelected(undefined)} size="large">
   <Alert type="info" title="活动仍绑定指定受众快照；需发布新活动版本才能切换到新受众。" description={`受众标识：${selected?.audienceId??""}`} style={{marginBottom:16}}/>
   <ErrorNotice error={runs.error}/><Space><Button onClick={runs.refresh}>刷新进度</Button><ActionButton label="执行一批" path="/admin/segments/pump" onDone={refresh}/></Space>
   <Table<Run> rowKey="runId" dataSource={runs.data} loading={runs.loading} pagination={false} scroll={{x:850}} columns={[
    {title:"受众版本",dataIndex:"snapshotVersion"},{title:"规则版本",dataIndex:"definitionVersion"},{title:"扫描 / 命中",render:(_,r)=>`${r.processed} / ${r.matched}`},
    {title:"状态",dataIndex:"status",render:v=><Status value={v}/>},{title:"重试次数",dataIndex:"attempts"},{title:"入组事件",render:(_,r)=>r.status!=="COMPLETED"?"未发布":r.entriesAnnounced?"已完成":r.entryAttempts>=5?"已隔离":"发送中"},{title:"失败分类",dataIndex:"errorCode"},
    {title:"扫描起点",dataIndex:"startedAt",render:time},{title:"有效期至",dataIndex:"validUntil",render:time},
    {title:"操作",render:(_,r)=><Space>
     {["RUNNING","ISOLATED"].includes(r.status)&&<ActionButton label="取消" path={`/admin/segment-runs/${encode(r.runId)}/cancel`} onDone={refresh}/>}
     {r.status==="COMPLETED"&&r.entryAttempts>=5&&<ActionButton label="重试入组事件" path={`/admin/segment-runs/${encode(r.runId)}/retry-announcement`} onDone={refresh}/>}
     {r.status==="ISOLATED"&&<ActionButton label="从检查点重试" path={`/admin/segment-runs/${encode(r.runId)}/retry`} onDone={refresh}/>}
    </Space>}
   ]}/><Space><Button onClick={()=>setRunAfter("")}>回到首页</Button><Button disabled={runs.data?.length!==50} onClick={()=>setRunAfter(runs.data!.at(-1)!.runId)}>下一页</Button></Space>
  </Drawer>
  <Modal title="发布人群定义版本" open={open} onCancel={()=>!command.busy&&setOpen(false)} footer={null} width={780} destroyOnHidden>
   <ErrorNotice error={command.error}/>
   <Form form={form} layout="vertical" initialValues={{version:1,ttlSeconds:3600,refreshSeconds:0,maxMembers:10000}} onFinish={async v=>{if(await command.run("/admin/segments",v)){setOpen(false);refresh();}}}>
    <Fields fields={[{name:"segmentId",label:"人群标识"},{name:"version",label:"定义版本",type:"number",min:1},{name:"name",label:"名称"},
     {name:"ttlSeconds",label:"快照有效秒数",type:"number",min:300,max:86400},{name:"refreshSeconds",label:"自动刷新间隔秒数（0为手工）",type:"number",min:0,max:86400},{name:"maxMembers",label:"单次扫描会员上限",type:"number",min:100,max:100000}]}/>
    <Form.Item name="rule" label="圈选条件" rules={[{required:true,message:"请配置圈选条件"}]}><RuleEditor memberOnly/></Form.Item>
    <Alert type="info" title="新定义发布后默认手工刷新；检查结果后再启用周期刷新。" style={{marginBottom:16}}/>
    <Button type="primary" htmlType="submit" loading={command.busy}>发布定义</Button>
   </Form>
  </Modal>
 </>;
}
