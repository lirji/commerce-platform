import { test, expect, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { randomUUID } from "node:crypto";
const access=JSON.parse(readFileSync(new URL("../../.local/operations-access.json",import.meta.url),"utf8"));
const evidence=process.env.COMMERCE_EVIDENCE_DIR??"../.local/operations-evidence";
type Role="admin"|"operator"|"member";
async function api(path:string,body?:unknown,role:Role="admin",status=200){
 const response=await fetch(access.baseUrl+"/v1"+path,{method:body===undefined?"GET":"POST",headers:{Authorization:"Bearer "+access[role+"Token"],"Content-Type":"application/json","Idempotency-Key":randomUUID()},...(body===undefined?{}:{body:JSON.stringify(body)})});
 expect(response.status,`${path} HTTP status`).toBe(status);return response.json();
}
async function login(page:Page,role:Role="admin"){
 await page.goto("/");await page.getByLabel("访问凭据",{exact:true}).fill(access[role+"Token"]);await page.getByRole("button",{name:"进入平台",exact:true}).click();await expect(page.getByRole("button",{name:"退出",exact:true})).toBeVisible();
}
async function nav(page:Page,label:string){await page.getByRole("menuitem",{name:label,exact:true}).click();}
async function submit(page:Page){await page.getByRole("button",{name:"确认提交",exact:true}).click();await expect(page.getByRole("dialog")).toHaveCount(0);}
async function events(){for(let i=0;i<8;i++){if(await api("/admin/events/pump",null)===0)break;}}

test("会员生命周期：资料、冻结恢复、终态及审计记录",async({page})=>{
 await login(page);await nav(page,"会员档案");
 const row=page.getByRole("row").filter({hasText:"lifecycle-member"});
 await row.getByRole("button",{name:"编辑资料",exact:true}).click();await page.getByLabel("显示名称",{exact:true}).fill("生命周期已核验");await page.getByLabel("变更原因",{exact:true}).fill("浏览器资料修订");await submit(page);
 await expect(row).toContainText("生命周期已核验");
 for(const option of ["冻结","恢复正常","注销（不可恢复，保留交易记录）"]){
  await row.getByRole("button",{name:"变更状态",exact:true}).click();await page.getByLabel("目标状态",{exact:true}).click();await page.locator(".ant-select-item-option-content").filter({hasText:new RegExp("^"+option.replace(/[()]/g,"\\$&")+"$")}).click();await page.getByLabel("变更原因",{exact:true}).fill("浏览器状态验收："+option);await submit(page);
 }
 await expect(row.getByRole("button",{name:"变更状态",exact:true})).toBeDisabled();
 await row.getByRole("button",{name:"变更记录",exact:true}).click();await expect(page.getByRole("dialog")).toContainText("浏览器资料修订");await page.screenshot({path:`${evidence}/operations-member-history.png`,fullPage:true});
});

test("门店运营：商品规格、调价上下架与当前会话即时撤权",async({page})=>{
 await login(page,"operator");await expect(page.getByRole("menuitem")).toHaveCount(1);await expect(page.getByText("精品咖啡小盒",{exact:true})).toBeVisible();
 const stores=await api("/operations/stores",undefined,"operator");expect(stores.map((s:{storeId:string})=>s.storeId)).toEqual(["brand-store"]);
 await page.getByRole("tab",{name:"商品资料（SPU）",exact:true}).click();await page.getByRole("button",{name:"创建商品",exact:true}).click();
 for(const [label,value] of [["商品标识","ui-product"],["商品名称","浏览器规格商品"],["分类","随行器具"],["品牌","日常品牌"]])await page.getByLabel(label,{exact:true}).fill(value);
 await submit(page);await expect(page.getByText("浏览器规格商品",{exact:true})).toBeVisible();
 await page.getByRole("tab",{name:"销售规格与上下架",exact:true}).click();await page.getByRole("button",{name:"创建销售规格",exact:true}).click();
 for(const [label,value] of [["SKU标识","ui-sku"],["所属商品标识（SPU）","ui-product"],["销售名称","浏览器白色杯"],["售价（元）","59.00"],["规格组合","颜色=白色\n容量=350ml"]])await page.getByLabel(label,{exact:true}).fill(value);
 await submit(page);const row=page.getByRole("row").filter({hasText:"ui-sku"});await expect(row).toContainText("白色");
 await row.getByRole("button",{name:"调价 / 上下架",exact:true}).click();await page.getByLabel("售价（元）",{exact:true}).fill("49.00");await page.getByLabel("销售状态",{exact:true}).click();await page.locator(".ant-select-item-option-content").filter({hasText:/^上架$/}).click();await page.getByLabel("修订原因",{exact:true}).fill("新品首发价格");await submit(page);await expect(row).toContainText("49.00");
 await row.getByRole("button",{name:"修订记录",exact:true}).click();await expect(page.getByRole("dialog")).toContainText("新品首发价格");await page.screenshot({path:`${evidence}/operations-product-history.png`,fullPage:true});await page.getByRole("button",{name:"关闭",exact:true}).click();
 const grant=(await api("/admin/store-grants"))[0];await api("/admin/store-grants/clerk-catalog/status",{expectedVersion:grant.version,active:false,reason:"验证在途会话撤权"});
 await page.getByRole("button",{name:"刷新",exact:true}).click();await expect(page.getByRole("alert").first()).toBeVisible();await api("/operations/skus?storeId=brand-store",undefined,"operator",403);
 await api("/admin/store-grants/clerk-catalog/status",{expectedVersion:grant.version+1,active:true,reason:"验收后恢复演示权限"});
});

test("成长经营：真实账本、升级与标签撤销恢复",async({page})=>{
 await login(page);await nav(page,"会员成长");await page.getByLabel("查询会员成长",{exact:true}).fill("ops-member");await page.getByRole("button",{name:"查询会员",exact:true}).click();
 await expect(page.getByText("BASIC",{exact:true})).toBeVisible();await page.getByRole("button",{name:"调整成长",exact:true}).click();await page.getByLabel("调整量（扣减填写负数）",{exact:true}).fill("100");await page.getByLabel("调整原因",{exact:true}).fill("经营验收成长校准");await submit(page);await expect(page.getByText("GOLD",{exact:true})).toBeVisible();await expect(page.getByText("经营验收成长校准",{exact:true})).toBeVisible();
 const tag=page.getByRole("row").filter({hasText:"coffee-lover"});
 for(const action of ["撤销","恢复"]){await tag.getByRole("button",{name:action,exact:true}).click();await page.getByLabel("变更原因",{exact:true}).fill("标签偏好校正");await submit(page);}
 await page.screenshot({path:`${evidence}/operations-growth.png`,fullPage:true});await page.getByRole("tab",{name:"成长与等级规则",exact:true}).click();await expect(page.getByText(/PLATINUM ≥ 500/)).toBeVisible();
});

test("人群与促销：复制圈选版本、检查刷新、只读预览与活动复用",async({page})=>{
 await login(page);await nav(page,"动态人群");await page.getByRole("button",{name:"复制为新定义",exact:true}).click();await expect(page.getByLabel("定义版本",{exact:true})).toHaveValue("2");await page.getByRole("button",{name:"发布定义",exact:true}).click();await expect(page.getByRole("dialog")).toHaveCount(0);
 await page.getByRole("button",{name:"开始刷新",exact:true}).click();await page.getByRole("button",{name:"执行一批",exact:true}).click();await expect(page.getByRole("dialog")).toContainText("2 / 1");await expect(page.getByRole("dialog")).toContainText("已完成");await page.screenshot({path:`${evidence}/operations-segment.png`,fullPage:true});await page.getByRole("button",{name:"关闭",exact:true}).click();
 await nav(page,"活动管理");await page.getByRole("button",{name:"预览优惠",exact:true}).click();await page.getByLabel("会员标识",{exact:true}).fill("ops-member");await page.getByLabel("购物清单",{exact:true}).fill("coffee-small,2");await page.getByRole("button",{name:"计算预览",exact:true}).click();await expect(page.getByText("符合活动条件",{exact:true})).toBeVisible();await expect(page.getByRole("dialog")).toContainText("¥180.00");await page.screenshot({path:`${evidence}/operations-promotion-preview.png`,fullPage:true});await page.getByRole("button",{name:"关闭",exact:true}).click();
 await page.getByRole("button",{name:"复制新版本",exact:true}).click();await page.getByRole("button",{name:"保存活动草稿",exact:true}).click();await expect(page.getByRole("row").filter({hasText:"草稿"})).toContainText("咖啡会员阶梯礼遇");
});

test("旅程与效果：升级通知、成交退款和历史补齐",async({page,browser})=>{
 await events();for(let i=0;i<3;i++)await api("/admin/journeys/pump",null);
 const notifications=await api("/notifications",undefined,"member");expect(notifications.some((n:{title:string})=>n.title==="欢迎成为金卡会员")).toBe(true);
 const member=await browser.newPage();await login(member,"member");await nav(member,"消息");await expect(member.getByText("欢迎成为金卡会员",{exact:true})).toBeVisible();await nav(member,"我的成长");await expect(member.getByText("GOLD",{exact:true})).toBeVisible();await member.close();
 const quote=await api("/quotes",{storeId:"brand-store",items:[{skuId:"coffee-small",quantity:1}]},"member");const order=await api("/orders",{quoteId:quote.quoteId,address:{recipient:"经营验收",phone:"13800000000",detail:"隔离测试地址"}},"member");
 const payment=await api(`/orders/${order.orderId}/payments`,null,"member");await api(`/admin/sandbox/payments/${payment.paymentId}/fact`,{status:"PAID"});await api(`/orders/${order.orderId}/payment/reconcile`,null,"member");await events();
 const returned=await api("/aftersales",{orderId:order.orderId,reason:"效果分析验收",items:[{skuId:"coffee-small",quantity:1}]},"member");const approved=await api(`/admin/aftersales/${returned.caseId}/approve`,null);await api(`/admin/sandbox/refunds/${approved.refundId}/success`,null);await api(`/admin/refunds/${approved.refundId}/reconcile`,null);await events();
 await login(page);await nav(page,"营销效果");const row=page.getByRole("row").filter({hasText:"coffee-growth / v1"});await expect(row).toBeVisible();await expect(row).toContainText("¥90.00");await expect(row).toContainText("¥0.00");await expect(row).toContainText("¥5.00");await page.screenshot({path:`${evidence}/operations-effects.png`,fullPage:true});
 await page.getByRole("tab",{name:"旅程执行",exact:true}).click();await expect(page.getByRole("row").filter({hasText:"growth-welcome"})).toBeVisible();await page.getByRole("tab",{name:"补齐历史订单",exact:true}).click();await page.getByRole("button",{name:"补齐下一批",exact:true}).click();await expect(page.getByText("本轮历史订单已扫描到末尾",{exact:true})).toBeVisible();
});


test("旅程运营：配置注册触发与频控、审批发布及真实入组",async({page})=>{
 await login(page);await nav(page,"营销旅程");await page.getByRole("button",{name:"创建旅程",exact:true}).click();
 await page.getByLabel("旅程标识",{exact:true}).fill("ui-registration");await page.getByLabel("旅程名称",{exact:true}).fill("注册欢迎实验");await page.getByLabel("触发方式",{exact:true}).click();await page.locator(".ant-select-item-option-content").filter({hasText:/^会员注册$/}).click();await page.getByLabel("窗口内最多通知次数",{exact:true}).fill("1");
 await page.getByLabel("节点1标识",{exact:true}).fill("welcome");await page.getByLabel("节点1类型",{exact:true}).click();await page.getByText("站内触达",{exact:true}).click();await page.getByLabel("消息标题",{exact:true}).fill("新会员欢迎实验");await page.getByLabel("消息内容",{exact:true}).fill("注册触发通过频控后发送站内欢迎。");await page.getByRole("button",{name:"添加节点",exact:true}).click();await page.getByLabel("节点1下一节点",{exact:true}).click();await page.locator(".ant-select-item-option-content").filter({hasText:/^node-2$/}).click();await page.getByRole("button",{name:"保存旅程草稿",exact:true}).click();
 const row=page.getByRole("row").filter({hasText:"注册欢迎实验"});for(const name of ["提交审批","批准","发布"])await row.getByRole("button",{name,exact:true}).click();await expect(row).toContainText("已发布");
 await api("/admin/members",{memberId:"newly-registered",actorId:"new-buyer",displayName:"新注册会员",memberLevel:"BASIC"});await events();for(let i=0;i<3;i++)await api("/admin/journeys/pump",null);
 await nav(page,"旅程实例");await expect(page.getByRole("row").filter({hasText:"ui-registration"})).toContainText("已完成");await page.screenshot({path:`${evidence}/operations-journey.png`,fullPage:true});
});
