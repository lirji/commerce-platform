import { test, expect } from "@playwright/test";
import { readFileSync } from "node:fs";
import { navigate } from "./navigation";
const access=JSON.parse(readFileSync(new URL("../../.local/member-suite-access.json",import.meta.url),"utf8"));
const evidence=process.env.COMMERCE_EVIDENCE_DIR??"../.local/member-suite-evidence";
test("深色经营总览：真实汇总、每日趋势、导航搜索与手机布局",async({page})=>{
 const errors:string[]=[];page.on("pageerror",e=>errors.push(e.message));await page.goto("/");await page.getByLabel("访问凭据",{exact:true}).fill(access.adminToken);await page.getByRole("button",{name:"进入平台",exact:true}).click();await expect(page.getByRole("heading",{name:"经营总览",exact:true})).toBeVisible();
 const response=await page.request.get(access.baseUrl+"/v1/admin/dashboard?storeId=brand-store",{headers:{Authorization:"Bearer "+access.adminToken}});expect(response.ok()).toBe(true);const result=await response.json();await expect(page.locator(".metric-card").filter({hasText:"净收金额"})).toContainText(`¥${result.totals.netReceipts}`);expect(Number(result.totals.received)).toBeGreaterThan(0);expect(result.daily).toHaveLength(30);await expect(page.locator(".trend-column")).toHaveCount(30);
 await page.getByRole("button",{name:"查看每日数据",exact:true}).click();await expect(page.getByRole("columnheader",{name:"UTC日期",exact:true})).toBeVisible();await page.getByRole("button",{name:"收起每日数据",exact:true}).click();await page.screenshot({path:`${evidence}/dashboard-desktop.png`,fullPage:true});
 await navigate(page,"商品管理");await expect(page.getByRole("heading",{name:"商品经营",exact:true})).toBeVisible();await navigate(page,"经营总览");
 await page.setViewportSize({width:390,height:844});await expect(page.getByRole("button",{name:"打开经营导航",exact:true})).toBeVisible();await expect.poll(()=>page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1)).toBe(true);await page.screenshot({path:`${evidence}/dashboard-mobile.png`,fullPage:true});
 await page.getByRole("button",{name:"打开经营导航",exact:true}).click();await expect(page.getByRole("dialog",{name:"经营导航",exact:true})).toBeVisible();await page.getByRole("button",{name:"关闭",exact:true}).click();
 await page.route("**/v1/admin/dashboard?*",r=>r.fulfill({status:503,contentType:"application/json",body:JSON.stringify({message:"总览暂时不可用"})}));await page.getByRole("button",{name:"刷新总览",exact:true}).click();await expect(page.getByText("总览暂时不可用",{exact:true})).toBeVisible();await expect(page.locator(".metric-card")).toHaveCount(0);expect(errors).toEqual([]);
});
