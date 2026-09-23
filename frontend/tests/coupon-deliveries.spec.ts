import { test, expect, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
const access = JSON.parse(readFileSync(new URL("../../.local/member-suite-access.json", import.meta.url), "utf8"));
const evidence = process.env.COMMERCE_EVIDENCE_DIR ?? "../.local/member-suite-evidence";
async function login(page: Page, admin: boolean) {
  await page.goto("/");await page.getByLabel("访问凭据", { exact: true }).fill(admin ? access.adminToken : access.deliveryToken);
  await page.getByRole("button", { name: "进入平台", exact: true }).click();
}
test("定向发券：固定人群、相对期限、真实钱包与撤销回执", async ({ page, browser }) => {
  await login(page, true);await page.getByRole("menuitem", { name: "定向发券", exact: true }).click();
  await page.getByRole("button", { name: "创建定向发券", exact: true }).click();
  await page.getByLabel("发券批次标识", { exact: true }).fill("ui-delivery");await page.getByLabel("发券批次名称", { exact: true }).fill("浏览器会员关怀");
  await page.getByLabel("受控券定义", { exact: true }).click();await page.getByText("会员关怀受控券 · v1", { exact: true }).click();
  await page.getByLabel("固定人群快照", { exact: true }).click();await page.getByText(/^会员关怀演示人群 · v1 · 到期/).click();
  const d = new Date(Date.now() + 3600000);const local = new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
  await page.getByLabel("发券截止时间", { exact: true }).fill(local);
  await page.getByRole("button", { name: "确认提交", exact: true }).click();
  const row = page.getByRole("row").filter({ hasText: "ui-delivery" });
  await expect(row).toContainText("运行中");await page.getByRole("button", { name: "推进一批发券", exact: true }).click();
  await expect(row).toContainText("已完成");await expect(row).toContainText("2 / 2 / 0");
  const member = await browser.newPage();await login(member, false);await member.getByRole("menuitem", { name: "优惠券", exact: true }).click();
  await expect(member.getByRole("menuitem", { name: "定向发券", exact: true })).toHaveCount(0);
  await member.getByRole("tab", { name: "我的优惠券", exact: true }).click();
  await expect(member.getByText("会员关怀受控券", { exact: true })).toBeVisible();
  const walletResponse = await fetch(access.baseUrl + "/v1/coupons", { headers: { Authorization: "Bearer " + access.deliveryToken } });expect(walletResponse.status).toBe(200);
  const coupon = (await walletResponse.json())[0];expect(Date.parse(coupon.validTo) - Date.parse(coupon.validFrom)).toBe(7 * 86400000);
  await member.screenshot({ path: `${evidence}/targeted-coupon-wallet.png`, fullPage: true });
  await row.getByRole("button", { name: "撤销可用券", exact: true }).click();await page.getByLabel("批次操作原因", { exact: true }).fill("浏览器撤销未使用关怀券");
  await page.getByRole("button", { name: "确认提交", exact: true }).click();await expect(row).toContainText("撤销处理中");
  await page.getByRole("button", { name: "推进一批发券", exact: true }).click();await expect(row).toContainText("撤销处理完成");
  await row.getByRole("button", { name: "发券回执", exact: true }).click();await expect(page.getByRole("dialog")).toContainText("撤销 2");
  await expect(page.getByRole("dialog").getByRole("row").filter({ hasText: "delivery-member" })).toContainText("已撤销");
  await page.screenshot({ path: `${evidence}/targeted-coupon-revocation.png`, fullPage: true });await member.close();
});
