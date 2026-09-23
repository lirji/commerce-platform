import { test, expect, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
const access = JSON.parse(readFileSync(new URL("../../.local/member-suite-access.json", import.meta.url), "utf8"));
const evidence = process.env.COMMERCE_EVIDENCE_DIR ?? "../.local/member-suite-evidence";
async function openPoints(page: Page, role: "admin" | "member") {
  await page.goto("/");
  await page.getByLabel("访问凭据", { exact: true }).fill(access[role + "Token"]);
  await page.getByRole("button", { name: "进入平台", exact: true }).click();
  await page.getByRole("menuitem", { name: role === "admin" ? "会员成长" : "我的成长", exact: true }).click();
  await page.getByRole("tab", { name: "积分账户", exact: true }).click();
}
test("积分经营与会员账本：真实余额、版本校准和无管理越权入口", async ({ page, browser }) => {
  await openPoints(page, "admin");
  await page.getByLabel("查询会员积分", { exact: true }).fill("suite-member");
  await page.getByRole("button", { name: "查看积分", exact: true }).click();
  const available = page.locator(".ant-statistic").filter({ hasText: "可用积分" });
  await expect(available).toContainText("1,200");
  await page.getByRole("button", { name: "校准积分", exact: true }).click();
  await page.getByLabel("积分调整量", { exact: true }).fill("-200");
  await page.getByLabel("积分调整原因", { exact: true }).fill("浏览器积分校准验收");
  await page.getByRole("button", { name: "确认提交", exact: true }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(available).toContainText("1,000");
  await expect(page.getByText("浏览器积分校准验收", { exact: true })).toBeVisible();
  await page.screenshot({ path: `${evidence}/member-points-admin.png`, fullPage: true });
  const member = await browser.newPage();
  await openPoints(member, "member");
  await expect(member.locator(".ant-statistic").filter({ hasText: "可用积分" })).toContainText("1,000");
  await expect(member.getByRole("button", { name: "校准积分", exact: true })).toHaveCount(0);
  await expect(member.getByText("浏览器积分校准验收", { exact: true })).toBeVisible();
  await member.screenshot({ path: `${evidence}/member-points-wallet.png`, fullPage: true });
  await member.close();
});
