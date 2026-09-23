import { Alert, Button, Card, Descriptions, Input, Space, Table, Tabs } from "antd";
import { MemberPoints } from "./MemberPoints";
import { MemberCycles } from "./MemberCycles";
import { useState } from "react";
import { encode, useResource } from "../shared/api";
import { ActionButton, CommandModal, ErrorNotice, PageHead, instant, money, time } from "../shared/ui";
type Wallet={memberId:string;growth:number;netSpend:string;memberLevel:string;policyVersion:number;version:number};
type Entry={sequenceId:number;sourceId:string;delta:number;balance:number;policyVersion:number;reason:string;createdAt:string};
type Policy={version:number;effectiveFrom:string;growthPerYuan:string;levels:{code:string;minimumGrowth:number}[]};
type Assignment={tagId:string;active:boolean;version:number;source:string;reason:string};

/** 经营与会员视图共用真实账本，配置发布不在浏览器计算成长。 */
export function MemberGrowth({admin}:{admin:boolean}) {
 const [member,setMember]=useState("");const [tagAfter,setTagAfter]=useState("");const [after,setAfter]=useState(0);const [policyAfter,setPolicyAfter]=useState(0);
 const base=admin?(member?"/admin/member-growth/"+encode(member):null):"/members/me/growth";
 const wallet=useResource<Wallet>(base);
 const ledger=useResource<Entry[]>(base?`${base}/ledger?after=${after}`:null);
 const policies=useResource<Policy[]>(admin?`/admin/member-growth/policies?after=${policyAfter}`:null);
 const assignments=useResource<Assignment[]>(admin&&member?`/admin/member-tags/${encode(member)}/assignments?after=${encode(tagAfter)}`:null);
 const refresh=()=>{wallet.refresh();ledger.refresh();assignments.refresh();};
 return <>
  <PageHead title={admin?"会员成长经营":"我的成长"} description="成长来自完成订单的净消费，成功退款按原规则冲回。成长不是可提现余额。"/>
  <ErrorNotice error={wallet.error}/><ErrorNotice error={ledger.error}/><ErrorNotice error={policies.error}/>
  <Tabs defaultActiveKey="wallet" items={[
   {key:"points",label:"积分账户",children:<MemberPoints admin={admin}/>},
   {key:"cycles",label:"周期与等级权益",children:<MemberCycles admin={admin}/>},
   {key:"wallet",label:"成长与账本",children:<Card>
    {admin&&<Input.Search aria-label="查询会员成长" placeholder="输入会员标识查看成长" enterButton="查询会员" onSearch={v=>{setMember(v.trim());setAfter(0);setTagAfter("");}} style={{maxWidth:440,marginBottom:16}}/>}
    {wallet.data?<>
     <Descriptions items={[
      {key:"level",label:"等级",children:wallet.data.memberLevel},{key:"growth",label:"成长值",children:wallet.data.growth},
      {key:"net",label:"完成订单净消费",children:money(wallet.data.netSpend)},{key:"version",label:"等级规则版本",children:wallet.data.policyVersion||"未启用"}]}/>
     {admin&&<Space wrap>
      <CommandModal title="调整成长" path={base+"/adjust"} fields={[{name:"delta",label:"调整量（扣减填写负数）",type:"number",min:-1000000000,max:1000000000},{name:"reason",label:"调整原因"}]} build={v=>({...v,expectedVersion:wallet.data!.version})} onDone={refresh}/>
      <ActionButton label="按当前规则重算等级" path={base+"/recalculate"} onDone={refresh}/>
     </Space>}
     <Button onClick={refresh}>刷新账本</Button>
     <Table<Entry> rowKey="sequenceId" dataSource={ledger.data} loading={ledger.loading} pagination={false} scroll={{x:800}} columns={[
      {title:"来源",dataIndex:"sourceId"},{title:"变动",dataIndex:"delta"},{title:"余额",dataIndex:"balance"},{title:"成长率版本",dataIndex:"policyVersion"},{title:"原因",dataIndex:"reason"},{title:"时间",dataIndex:"createdAt",render:time}]}/>
     <Space><Button onClick={()=>setAfter(0)} disabled={after===0}>最早记录</Button><Button disabled={ledger.data?.length!==50} onClick={()=>setAfter(ledger.data!.at(-1)!.sequenceId)}>下一页</Button></Space>
     {admin&&<>
      <h3>会员标签</h3><ErrorNotice error={assignments.error}/>
      <CommandModal title="设置标签" path={`/admin/member-tags/${encode(member)}/assign`} fields={[
       {name:"tagId",label:"标签标识（须已在字典创建）"},{name:"expectedVersion",label:"关联版本（首次填0）",type:"number",initial:0},
       {name:"active",label:"启用",type:"switch",initial:true},{name:"reason",label:"变更原因"}]} onDone={refresh}/>
      <Table<Assignment> rowKey="tagId" dataSource={assignments.data} pagination={false} columns={[
       {title:"标签",dataIndex:"tagId"},{title:"生效",dataIndex:"active",render:v=>v?"是":"否"},{title:"版本",dataIndex:"version"},{title:"来源",dataIndex:"source"},{title:"原因",dataIndex:"reason"},
       {title:"操作",render:(_,r)=><CommandModal title={r.active?"撤销":"恢复"} buttonType="link" path={`/admin/member-tags/${encode(member)}/assign`} fields={[{name:"reason",label:"变更原因"}]} build={v=>({...v,tagId:r.tagId,expectedVersion:r.version,active:!r.active})} onDone={refresh}/>}
      ]}/>
      <Space><Button disabled={!tagAfter} onClick={()=>setTagAfter("")}>标签首页</Button><Button disabled={assignments.data?.length!==50} onClick={()=>setTagAfter(assignments.data!.at(-1)!.tagId)}>下一页标签</Button></Space>
     </>}
    </>:<Alert type="info" title={admin?"选择会员后查看成长与标签":"正在读取会员成长"}/>}
   </Card>},
   ...(admin?[{key:"policies",label:"成长与等级规则",children:<Card>
    <Alert type="info" title="新版本用于生效时间之后创建的订单；历史订单退款继续使用原成长率。等级在成长变更或显式重算时更新。" style={{marginBottom:16}}/>
    <CommandModal title="发布成长规则" path="/admin/member-growth/policies" fields={[
     {name:"version",label:"新版本号",type:"number",min:1},{name:"effectiveFrom",label:"生效时间",type:"datetime"},
     {name:"growthPerYuan",label:"每元成长值（0–1000，最多两位小数）",type:"money"},
     {name:"levels",label:"等级门槛",type:"textarea",help:"按门槛升序，每行 等级代码=成长门槛。首档为0，最多8档。"}]}
     build={v=>({...v,effectiveFrom:instant(v.effectiveFrom),levels:String(v.levels).split("\n").filter(v=>v.trim()).map(line=>{
      const [code,raw,...extra]=line.split("=");const minimumGrowth=Number(raw);if(extra.length||!code.trim()||raw===undefined||!Number.isSafeInteger(minimumGrowth))throw new Error("等级请使用 等级代码=整数门槛 格式");return {code:code.trim(),minimumGrowth};})})} onDone={policies.refresh}/>
    <Table<Policy> rowKey="version" dataSource={policies.data} pagination={false} columns={[
     {title:"版本",dataIndex:"version"},{title:"生效时间",dataIndex:"effectiveFrom",render:time},{title:"每元成长",dataIndex:"growthPerYuan"},
     {title:"等级门槛",render:(_,r)=>r.levels.map(l=>`${l.code} ≥ ${l.minimumGrowth}`).join(" / ")}]}/>
    <Space><Button onClick={()=>setPolicyAfter(0)}>最早版本</Button><Button disabled={policies.data?.length!==50} onClick={()=>setPolicyAfter(policies.data!.at(-1)!.version)}>下一页</Button></Space>
   </Card>}]:[])
  ]}/>
 </>;
}
