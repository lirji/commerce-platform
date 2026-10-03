import { accessPath } from "./access";
import { expect, test, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { navigate } from "./navigation";

const access = JSON.parse(
  readFileSync(
    process.env.COMMERCE_VISUAL_ACCESS ??
      accessPath("member-suite-access.json"),
    "utf8",
  ),
);

async function login(page: Page, admin: boolean) {
  await page.goto("/");
  await page
    .getByLabel("访问凭据", { exact: true })
    .fill(admin ? access.adminToken : access.memberToken);
  await page.getByRole("button", { name: "进入平台", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "退出", exact: true }),
  ).toBeVisible();
}

async function fitsViewport(page: Page) {
  await expect
    .poll(() =>
      page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth + 1,
      ),
    )
    .toBe(true);
}

test("窄屏商品表格内部滚动，字段错误可见且关闭弹层归还焦点", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await login(page, true);
  await navigate(page, "商品管理");
  await expect(page.locator(".status-tag").first()).toBeVisible();
  await fitsViewport(page);
  const table = page.locator(".ant-table-content").first();
  expect(await table.evaluate((el) => el.scrollWidth > el.clientWidth)).toBe(
    true,
  );
  await page.getByRole("tab", { name: "商品资料（SPU）", exact: true }).click();
  const opener = page.getByRole("button", { name: "创建商品", exact: true });
  await opener.click();
  await page.getByRole("button", { name: "确认提交", exact: true }).click();
  await expect(page.getByText("请输入商品标识", { exact: true })).toBeVisible();
  await page.getByLabel("商品标识", { exact: true }).focus();
  await page.keyboard.press("Tab");
  await expect(page.getByLabel("商品名称", { exact: true })).toBeFocused();
  await fitsViewport(page);
  await page.getByRole("button", { name: "关闭", exact: true }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(opener).toBeFocused();
});

test("手机商城图片、缺图提示和统一按钮尺寸，购物袋展示真实报价", async ({
  page,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.setViewportSize({ width: 390, height: 844 });
  await login(page, false);
  const card = page
    .locator(".product-card")
    .filter({ hasText: "积分精品咖啡" });
  await expect(card).toBeVisible();
  await fitsViewport(page);
  await expect
    .poll(() =>
      card
        .locator("img")
        .evaluate(
          (img: HTMLImageElement) => img.complete && img.naturalWidth > 0,
        ),
    )
    .toBe(true);
  await expect(page.locator(".product-fallback").first()).toContainText(
    "图片待补充",
  );
  for (const control of [
    card.getByRole("button", { name: "加入购物袋", exact: true }),
    page.getByRole("button", { name: "全部商品", exact: true }),
  ]) {
    const bounds = await control.boundingBox();
    expect(bounds!.height).toBe(38);
    expect(bounds!.width).toBeGreaterThanOrEqual(44);
  }
  await card.getByRole("button", { name: "加入购物袋", exact: true }).click();
  await page.getByRole("button", { name: "购物袋 · 1", exact: true }).click();
  const response = page.waitForResponse(
    (r) => r.url().endsWith("/v1/quotes") && r.request().method() === "POST",
  );
  await page
    .getByRole("button", { name: "计算优惠并结算", exact: true })
    .click();
  const quoteResponse = await response;
  expect(quoteResponse.ok()).toBe(true);
  const quote = await quoteResponse.json();
  await expect(
    page.getByRole("button", {
      name: `确认下单 · ¥${quote.payable}`,
      exact: true,
    }),
  ).toBeVisible();
  await expect(page.getByText("应付金额", { exact: true })).toBeVisible();
  await fitsViewport(page);
  await page.getByRole("button", { name: "关闭", exact: true }).click();
  expect(errors).toEqual([]);
});
