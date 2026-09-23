import { test, expect, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { randomUUID } from "node:crypto";
const access = JSON.parse(readFileSync(new URL("../../.local/member-suite-access.json", import.meta.url), "utf8"));
const evidence = process.env.COMMERCE_EVIDENCE_DIR ?? "../.local/member-suite-evidence";
async function open(page: Page, admin: boolean) {
  await page.goto("/"); await page.getByLabel("访问凭据", { exact: true }).fill(admin ? access.adminToken : access.exchangeToken);
  await page.getByRole("button", { name: "进入平台", exact: true }).click();
  await page.getByRole("menuitem", { name: admin ? "会员成长" : "我的成长", exact: true }).click();
  await page.getByRole("tab", { name: "积分兑换", exact: true }).click();
}
async function api(path: string, token: string, body?: unknown) {
  const r = await fetch(access.baseUrl + "/v1" + path, { method: body === undefined ? "GET" : "POST", headers: { Authorization: "Bearer " + token, "Content-Type": "application/json", "Idempotency-Key": randomUUID() }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
  expect(r.status).toBe(200); return r.json();
}
test("积分兑换：券与权益真实扣分、回执、钱包到账及经营停启", async ({ page, browser }) => {
  await open(page, false);
  const balance = page.locator(".ant-statistic").filter({ hasText: "当前可兑换积分" });
  await expect(balance).toContainText("2,000");
  for (const [name, cost] of [["积分专享五元券", 200], ["咖啡双杯礼遇", 500]]) {
    await page.locator(".point-offer-card").filter({ hasText: name }).getByRole("button", { name: `兑换 · ${cost} 积分`, exact: true }).click();
    await page.getByRole("button", { name: "确认提交", exact: true }).click();
    await expect(page.getByRole("dialog")).toHaveCount(0);
  }
  await expect(balance).toContainText("1,300");
  await expect(page.getByRole("row").filter({ hasText: "exclusive-coupon" })).toContainText("200");
  for (let i = 0; i < 4; i++) await api("/admin/events/pump", access.adminToken, null);
  expect((await api("/coupons", access.exchangeToken)).length).toBe(1);
  expect((await api("/entitlements", access.exchangeToken))[0].status).toBe("AVAILABLE");
  await page.screenshot({ path: `${evidence}/points-exchange-member.png`, fullPage: true });
  const admin = await browser.newPage(); await open(admin, true);
  const row = admin.getByRole("row").filter({ hasText: "exclusive-coupon" });
  await expect(row).toContainText("1 / 100");
  await row.getByRole("button", { name: "停用兑换", exact: true }).click();
  await admin.getByLabel("兑换状态变更原因", { exact: true }).fill("浏览器停止新兑换验证");
  await admin.getByRole("button", { name: "确认提交", exact: true }).click();
  await expect(row).toContainText("已停用");
  await page.getByRole("button", { name: "刷新兑换", exact: true }).click();
  await expect(page.locator(".point-offer-card").filter({ hasText: "积分专享五元券" })).toHaveCount(0);
  await row.getByRole("button", { name: "启用兑换", exact: true }).click();
  await admin.getByLabel("兑换状态变更原因", { exact: true }).fill("验收完成恢复演示");
  await admin.getByRole("button", { name: "确认提交", exact: true }).click();
  await expect(row).toContainText("已启用");
  await admin.getByRole("button", { name: "创建积分兑换", exact: true }).click();
  const localDate = (offset: number) => { const d = new Date(Date.now() + offset); return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16); };
  for (const [label, value] of [["兑换标识", "ui-point-offer"], ["兑换名称", "浏览器兑换玩法"], ["券定义或权益标识", "points-exclusive"], ["每次兑换所需积分", "100"], ["兑换总次数", "5"], ["兑换开始时间", localDate(60000)], ["兑换结束时间", localDate(3600000)]]) await admin.getByLabel(label, { exact: true }).fill(value);
  await admin.getByRole("button", { name: "确认提交", exact: true }).click();
  await expect(admin.getByRole("row").filter({ hasText: "ui-point-offer" })).toContainText("浏览器兑换玩法");
  await admin.screenshot({ path: `${evidence}/points-exchange-admin.png`, fullPage: true }); await admin.close();
});
