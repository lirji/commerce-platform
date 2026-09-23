import { test, expect } from "@playwright/test";
import { readFileSync } from "node:fs";
const access = JSON.parse(readFileSync(new URL("../../.local/member-suite-access.json", import.meta.url), "utf8"));
const evidence = process.env.COMMERCE_EVIDENCE_DIR ?? "../.local/member-suite-evidence";
async function wallet() {
  const r = await fetch(access.baseUrl + "/v1/members/me/points", { headers: { Authorization: "Bearer " + access.checkoutToken } });
  expect(r.status).toBe(200); return r.json();
}
test("积分结算：报价不占用、下单冻结、取消原路释放", async ({ page }) => {
  await page.goto("/");
  await page.getByLabel("访问凭据", { exact: true }).fill(access.checkoutToken);
  await page.getByRole("button", { name: "进入平台", exact: true }).click();
  await page.locator(".product-card").filter({ hasText: "积分精品咖啡" }).getByRole("button", { name: "加入购物袋" }).click();
  await page.getByRole("button", { name: "购物袋 · 1" }).click();
  await page.getByLabel("使用积分上限", { exact: true }).fill("1000");
  await page.getByRole("button", { name: "计算优惠并结算", exact: true }).click();
  await expect(page.getByText("1000 积分 / ¥10.00", { exact: true })).toBeVisible();
  expect((await wallet()).held).toBe(0);
  for (const [label, value] of [["收件人", "积分验收"], ["联系电话", "13800000000"], ["收货地址", "隔离验收地址"]]) await page.getByLabel(label, { exact: true }).fill(value);
  await page.screenshot({ path: `${evidence}/points-checkout.png`, fullPage: true });
  await page.getByRole("button", { name: "确认下单 · ¥15.00", exact: true }).click();
  await expect.poll(async () => (await wallet()).held).toBe(1000);
  await page.getByRole("button", { name: "查看详情", exact: true }).first().click();
  await expect(page.getByRole("columnheader", { name: "抵扣积分", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "取消订单", exact: true }).click();
  await expect.poll(async () => (await wallet()).held).toBe(0);
  expect((await wallet()).available).toBe(2000);
});
