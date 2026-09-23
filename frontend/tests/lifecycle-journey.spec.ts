import { test, expect, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
const access=JSON.parse(readFileSync(new URL("../../.local/member-suite-access.json",import.meta.url),"utf8"));
const evidence=process.env.COMMERCE_EVIDENCE_DIR??"../.local/member-suite-evidence";
async function login(page:Page,admin:boolean){await page.goto("/");await page.getByLabel("访问凭据",{exact:true}).fill(admin?access.adminToken:access.journeyToken);await page.getByRole("button",{name:"进入平台",exact:true}).click();}
test("生命周期：生日模板、审批、持久扫描、旅程券到账与效果比较",async({page,browser})=>{
 await login(page,true);await page.getByRole("menuitem",{name:"营销旅程",exact:true}).click();
 await page.getByLabel("生命周期模板",{exact:true}).click();await page.getByText("生日关怀",{exact:true}).click();
 await page.getByLabel("旅程标识",{exact:true}).fill("ui-birthday");await page.getByLabel("旅程名称",{exact:true}).fill("生日专属礼券旅程");
 await page.getByLabel("节点1类型",{exact:true}).click();await page.getByText("发放受控券",{exact:true}).click();
 await page.getByLabel("节点1券标识",{exact:true}).fill("target-care");await page.getByLabel("节点1下一节点",{exact:true}).click();await page.getByText("end",{exact:true}).last().click();
 await page.getByRole("button",{name:"保存旅程草稿",exact:true}).click();
 const row=page.getByRole("row").filter({hasText:"生日专属礼券旅程"});await expect(row).toContainText("草稿");
 for(const action of ["提交审批","批准","发布"]){await row.getByRole("button",{name:action,exact:true}).click();}
 await expect(row).toContainText("已发布");
 for(let i=0;i<5;i++){await page.getByRole("button",{name:"推进本租户旅程",exact:true}).click();await expect(page.getByRole("button",{name:"推进本租户旅程",exact:true})).toBeEnabled();}
 const scan=page.getByRole("row").filter({hasText:"ui-birthday / v1"});await expect(scan).toContainText("/ 1");
 await page.screenshot({path:`${evidence}/lifecycle-scanning.png`,fullPage:true});
 const member=await browser.newPage();await login(member,false);await member.getByRole("menuitem",{name:"优惠券",exact:true}).click();await member.getByRole("tab",{name:"我的优惠券",exact:true}).click();await expect(member.getByText("会员关怀受控券",{exact:true})).toBeVisible();
 await page.getByRole("menuitem",{name:"营销效果",exact:true}).click();await page.getByRole("tab",{name:"会员营销比较",exact:true}).click();
 const compared=page.getByRole("row").filter({hasText:"ui-birthday / v1"});await expect(compared).toBeVisible();await expect(compared).toContainText("0 / 1");await expect(page.getByText(/跨行不可相加/)).toBeVisible();
 await page.screenshot({path:`${evidence}/lifecycle-effects.png`,fullPage:true});await page.getByRole("tab",{name:"定向券批次比较",exact:true}).click();await expect(page.getByRole("columnheader",{name:"券自身优惠",exact:true})).toBeVisible();await member.close();
});
