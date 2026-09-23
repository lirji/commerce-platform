import { Button, Drawer, Space, Table } from "antd";
import { useState } from "react";
import { encode, useResource } from "../shared/api";
import { CommandModal, ErrorNotice, time } from "../shared/ui";

/** 资料与状态分开提交；冲突后刷新主列表再操作，避免覆盖他人决定。 */
export function MemberActions({row,onDone}:{row:Record<string,unknown>;onDone:()=>void}) {
 const [open,setOpen]=useState(false);
 const base="/admin/members/"+encode(String(row.memberId));
 const history=useResource<Record<string,unknown>[]>(open?base+"/history":null);
 const closed=row.status==="CLOSED";
 const done=()=>{onDone();history.refresh();};
 return <Space wrap>
  <CommandModal title="编辑资料" path={base+"/profile"} disabled={closed} buttonType="link"
   fields={[{name:"value",label:"显示名称",initial:row.displayName},{name:"reason",label:"变更原因"}]}
   build={v=>({...v,expectedVersion:row.version})} onDone={done}/>
  <CommandModal title="变更状态" path={base+"/status"} disabled={closed} buttonType="link"
   fields={[{name:"value",label:"目标状态",type:"select",options:[
    ...(row.status==="ACTIVE"?[{label:"冻结",value:"FROZEN"}]:[{label:"恢复正常",value:"ACTIVE"}]),
    {label:"注销（不可恢复，保留交易记录）",value:"CLOSED"}]},{name:"reason",label:"变更原因"}]}
   build={v=>({...v,expectedVersion:row.version})} onDone={done}/>
  <Button type="link" onClick={()=>setOpen(true)}>变更记录</Button>
  <Drawer title="会员变更记录（最近查询前50条）" open={open} onClose={()=>setOpen(false)} size="large">
   <ErrorNotice error={history.error}/><Button onClick={history.refresh}>刷新</Button>
   <Table rowKey="version" dataSource={history.data} loading={history.loading} pagination={false} columns={[
    {title:"版本",dataIndex:"version"},{title:"变更前",dataIndex:"beforeValue"},{title:"变更后",dataIndex:"afterValue"},
    {title:"原因",dataIndex:"reason"},{title:"操作人",dataIndex:"actorId"},{title:"时间",dataIndex:"createdAt",render:time}]}/>
  </Drawer>
 </Space>;
}
