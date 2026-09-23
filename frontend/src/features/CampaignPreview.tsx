import { Alert, Button, Descriptions, Drawer, Form, Table } from "antd";
import { useState } from "react";
import type { Campaign } from "../shared/contracts";
import { encode, useCommand } from "../shared/api";
import { ErrorNotice, Fields, instant, money } from "../shared/ui";
type Result={gross:string;discount:string;payable:string;lines:{skuId:string;gross:string;discount:string;payable:string}[];trace:{reason:string}[];notice:string};
const reasons:Record<string,string>={ELIGIBLE:"符合活动条件",OUTSIDE_VALIDITY:"不在活动有效期",OUTSIDE_PRODUCT_SCOPE:"没有参与商品",BELOW_MINIMUM:"未达到消费或阶梯门槛",CONDITION_NO_MATCH:"会员资格未命中",CONDITION_UNKNOWN:"资格事实缺失或受众快照已过期"};
/** 用真实会员和目录价格验证活动，不让运营手输可信等级或成交价格。 */
export function CampaignPreview({campaign}:{campaign:Campaign}){
 const [open,setOpen]=useState(false);const [result,setResult]=useState<Result>();const [form]=Form.useForm();const command=useCommand();
 return <><Button onClick={()=>{setOpen(true);setResult(undefined);command.clear();}}>预览优惠</Button>
  <Drawer title={`${campaign.name} · v${campaign.version} 预览`} open={open} onClose={()=>!command.busy&&setOpen(false)} size="large">
   <ErrorNotice error={command.error}/>
   <Form form={form} layout="vertical" onFinish={async v=>{
    setResult(undefined);
    try{const items=String(v.items).split("\n").filter(v=>v.trim()).map(line=>{const [skuId,quantity,...extra]=line.split(",").map(v=>v.trim());if(extra.length||!skuId||!Number.isInteger(Number(quantity))||Number(quantity)<=0)throw new Error("每行填写 SKU标识,正整数数量");return {skuId,quantity:Number(quantity)};});
     const value=await command.run<Result>(`/admin/campaigns/${encode(campaign.campaignId)}/${campaign.version}/preview`,{memberId:v.memberId,at:v.at?instant(v.at):null,items});if(value)setResult(value);
    }catch(e){form.setFields([{name:"items",errors:[e instanceof Error?e.message:"参数无效"]}]);}
   }}>
    <Fields fields={[{name:"memberId",label:"会员标识"},{name:"at",label:"观察活动时间（留空为现在）",type:"datetime",required:false},
     {name:"items",label:"购物清单",type:"textarea",help:"每行填写 SKU标识,数量。价格与会员事实由服务端读取。"}]}/>
    <Button type="primary" htmlType="submit" loading={command.busy}>计算预览</Button>
   </Form>
   {result&&<>
    <Alert type="info" title={result.trace.map(t=>reasons[t.reason]??t.reason).join("；")} description={result.notice} style={{marginBlock:16}}/>
    <Descriptions items={[{key:"gross",label:"商品原价",children:money(result.gross)},{key:"discount",label:"活动优惠",children:money(result.discount)},{key:"payable",label:"预计应付",children:money(result.payable)}]}/>
    <Table rowKey="skuId" dataSource={result.lines} pagination={false} columns={[{title:"SKU",dataIndex:"skuId"},{title:"原价",dataIndex:"gross",render:money},{title:"优惠",dataIndex:"discount",render:money},{title:"应付",dataIndex:"payable",render:money}]}/>
   </>}
  </Drawer></>;
}
