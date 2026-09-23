import { test, expect, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { randomUUID } from "node:crypto";
const access = JSON.parse(readFileSync(new URL("../../.local/member-suite-access.json", import.meta.url), "utf8"));
const evidence = process.env.COMMERCE_EVIDENCE_DIR ?? "../.local/member-suite-evidence";
async function login(page: Page, admin: boolean) {
  await page.goto("/"); await page.getByLabel("访问凭据", { exact: true }).fill(admin ? access.adminToken : access.behaviorToken);
  await page.getByRole("button", { name: "进入平台", exact: true }).click();
}
async function api(path: string, body?: unknown) {
  const r = await fetch(access.baseUrl + "/v1" + path, { method: body === undefined ? "GET" : "POST", headers: { Authorization: "Bearer " + access.adminToken, "Content-Type": "application/json", "Idempotency-Key": randomUUID() }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
  expect(r.status).toBe(200); return r.json();
}
test("行为经营：真实商品交互、本人偏好、会员详情与行为圈选", async ({ page, browser }) => {
  await login(page, false);
  const product = page.locator(".product-card").filter({ hasText: "积分精品咖啡" });
  await product.getByRole("button", { name: "查看商品", exact: true }).click();
  await page.getByRole("button", { name: "返回店铺", exact: true }).click();
  await product.getByRole("button", { name: "加入购物袋", exact: true }).click();
  await expect.poll(async () => (await api("/admin/member-behavior/behavior-member")).facts.cart30).toBe(1);
  await page.getByRole("menuitem", { name: "我的成长", exact: true }).click();
  await page.getByRole("tab", { name: "会员详情与行为", exact: true }).click();
  await expect(page.locator(".ant-statistic").filter({ hasText: "近30天浏览" })).toContainText("1");
  await page.getByRole("button", { name: "编辑生日与偏好", exact: true }).click();
  await page.getByLabel("生日月日", { exact: true }).fill("06-18");
  await page.getByRole("switch", { name: "接收站内营销旅程", exact: true }).click();
  await page.getByLabel("偏好变更原因", { exact: true }).fill("浏览器本人关闭营销旅程");
  await page.getByRole("button", { name: "确认提交", exact: true }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);await expect(page.getByText("已关闭", { exact: true })).toBeVisible();
  await page.screenshot({ path: `${evidence}/member-behavior-profile.png`, fullPage: true });
  const admin = await browser.newPage();await login(admin, true);
  await admin.getByRole("menuitem", { name: "会员档案", exact: true }).click();
  await admin.getByRole("row").filter({ hasText: "behavior-member" }).getByRole("button", { name: "会员详情", exact: true }).click();
  await expect(admin.getByRole("dialog")).toContainText("06-18");await expect(admin.getByRole("dialog")).toContainText("暂无成交事实");
  await admin.screenshot({ path: `${evidence}/member-behavior-admin.png`, fullPage: true });
  await admin.getByRole("button", { name: "关闭", exact: true }).click();
  await admin.getByRole("menuitem", { name: "动态人群", exact: true }).click();
  await admin.getByRole("button", { name: "发布人群定义", exact: true }).click();
  await admin.getByLabel("人群标识", { exact: true }).fill("ui-behavior-segment");await admin.getByLabel("名称", { exact: true }).fill("浏览后加购人群");
  await admin.getByLabel("规则字段", { exact: true }).click();await admin.getByText("30天加购次数", { exact: true }).click();
  await admin.getByLabel("比较值", { exact: true }).fill("1");
  await admin.getByRole("button", { name: "发布定义", exact: true }).click();
  const row = admin.getByRole("row").filter({ hasText: "ui-behavior-segment" });
  await row.getByRole("button", { name: "开始刷新", exact: true }).click();
  await admin.getByRole("button", { name: "执行一批", exact: true }).click();
  await expect(admin.getByRole("dialog")).toContainText("已完成");
  const runs = await api("/admin/segments/ui-behavior-segment/runs");expect(runs[0].matched).toBeGreaterThanOrEqual(1);
  await admin.close();
});
