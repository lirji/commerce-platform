import { navigate } from "./navigation";
import { test, expect, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
const access = JSON.parse(readFileSync(new URL("../../.local/member-suite-access.json", import.meta.url), "utf8"));
const evidence = process.env.COMMERCE_EVIDENCE_DIR ?? "../.local/member-suite-evidence";
async function login(page: Page, role = "admin") {
  await page.goto("/");
  await page.getByLabel("访问凭据", { exact: true }).fill(access[role + "Token"]);
  await page.getByRole("button", { name: "进入平台", exact: true }).click();
  await expect(page.getByRole("button", { name: "退出", exact: true })).toBeVisible();
}
test("周期经营：查询考核、礼包绑定与幂等补发", async ({ page }) => {
  await login(page);
  await navigate(page, "会员成长");
  await page.getByRole("tab", { name: "周期与等级权益", exact: true }).click();
  await page.getByLabel("查询会员周期", { exact: true }).fill("suite-member");
  await page.getByRole("button", { name: "查看周期", exact: true }).click();
  await expect(page.locator(".ant-statistic").filter({ hasText: "当前等级" })).toContainText("GOLD");
  await expect(page.getByText("gold-monthly", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "执行周期考核", exact: true }).click();
  const refreshed = page.waitForResponse(r => r.url().endsWith("/admin/member-cycles/suite-member") && r.request().method() === "GET");
  await page.getByRole("button", { name: "补发当前周期权益", exact: true }).click();
  await refreshed;
  await expect(page.locator(".ant-statistic").filter({ hasText: "当前等级" })).toContainText("GOLD");
  await page.screenshot({ path: `${evidence}/member-cycles-admin.png`, fullPage: true });
  await expect(page.locator("body")).toHaveCSS("color-scheme", "dark");
});
test("会员周期与权益钱包展示真实已发余额", async ({ page }) => {
  await login(page, "member");
  await navigate(page, "我的成长");
  await page.getByRole("tab", { name: "周期与等级权益", exact: true }).click();
  await expect(page.getByRole("tabpanel", { name: "周期与等级权益", exact: true }).getByText("120", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "执行周期考核", exact: true })).toHaveCount(0);
  await page.getByRole("link", { name: "查看我的权益", exact: true }).click();
  await expect(page.getByText("金卡每月咖啡礼遇", { exact: true })).toBeVisible();
  await page.screenshot({ path: `${evidence}/member-cycles-wallet.png`, fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  await page.screenshot({ path: `${evidence}/member-cycles-mobile.png`, fullPage: true });
});
