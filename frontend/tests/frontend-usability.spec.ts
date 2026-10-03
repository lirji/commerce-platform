import { expect, test, type Page } from "@playwright/test";
import { mkdirSync } from "node:fs";
import { assertButtonSizes, assertCentered } from "./presentation";

// 只在测试边界模拟正式会员DTO；数据库分页/授权另由真实MySQL用例验证。
async function members(page: Page) {
  const requests: URL[] = [];
  const records = Array.from({ length: 21 }, (_, i) => ({
    memberId: `m${String(i + 1).padStart(2, "0")}`,
    actorId: `actor-${i + 1}`,
    displayName: `会员${i + 1}`,
    memberLevel: "VIP",
    status: i === 20 ? "FROZEN" : "ACTIVE",
    version: 0,
  }));
  let fail = false;
  await page.route("**/v1/**", async (route) => {
    const url = new URL(route.request().url());
    let body: unknown = [];
    if (url.pathname === "/v1/me")
      body = { tenantId: "usability", actorId: "admin", role: "ADMIN" };
    if (url.pathname === "/v1/stores")
      body = [
        {
          storeId: "usability-store",
          merchantId: "merchant",
          name: "验收门店",
          status: "ACTIVE",
          version: 0,
        },
      ];
    if (url.pathname === "/v1/runtime-capabilities")
      body = { sandboxEnabled: false, workersEnabled: false };
    if (url.pathname === "/v1/admin/members") {
      requests.push(url);
      if (fail)
        return route.fulfill({
          status: 503,
          json: { message: "查询服务暂不可用", traceId: "usability-read" },
        });
      const q = url.searchParams.get("q") ?? "";
      const status = url.searchParams.get("status");
      const after = url.searchParams.get("after") ?? "";
      body = records
        .filter(
          (r) =>
            (!q || `${r.memberId} ${r.displayName}`.includes(q)) &&
            (!status || r.status === status) &&
            r.memberId > after,
        )
        .slice(0, Number(url.searchParams.get("limit") ?? 50));
    }
    await route.fulfill({ json: body });
  });
  await page.goto("/#members?store=usability-store");
  await page.getByLabel("访问凭据", { exact: true }).fill("usability-fixture");
  await page.getByRole("button", { name: "进入平台", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "会员档案", exact: true }),
  ).toBeVisible();
  return {
    requests,
    fail: () => {
      fail = true;
    },
  };
}

test("真实查询参数、上一页、刷新恢复和筛选重置，不伪造总数", async ({
  page,
}) => {
  const fixture = await members(page);
  await page.getByLabel("每页", { exact: true }).click();
  await page
    .locator(".ant-select-item-option-content")
    .getByText("10条", { exact: true })
    .click();
  await page.getByRole("button", { name: "查询", exact: true }).click();
  await expect(
    page.getByText("本页 10 条 · 每页最多10条", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "下一页", exact: true }).click();
  await expect(page.getByText("会员11", { exact: true })).toBeVisible();
  await expect(page.getByText("第2页", { exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByText("会员11", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "上一页", exact: true }).click();
  await expect(page.getByText("会员1", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "下一页", exact: true }).click();
  await page.getByLabel("关键词", { exact: true }).fill("会员21");
  await page.getByRole("button", { name: "查询", exact: true }).click();
  await expect(page.getByText("会员21", { exact: true })).toBeVisible();
  await expect(page.getByText("第1页", { exact: true })).toBeVisible();
  expect(fixture.requests.at(-1)?.searchParams.get("after")).toBe("");
  expect(fixture.requests.at(-1)?.searchParams.get("q")).toBe("会员21");
  expect(fixture.requests.at(-1)?.searchParams.get("limit")).toBe("10");
  await expect(
    page.getByRole("button", { name: "上一页", exact: true }),
  ).toBeDisabled();
  await page.getByRole("button", { name: "重置筛选", exact: true }).click();
  await expect(
    page.getByText("本页 21 条 · 每页最多50条", { exact: true }),
  ).toBeVisible();
  await page.getByLabel("状态", { exact: true }).click();
  await page
    .locator(".ant-select-item-option-content")
    .getByText("已冻结", { exact: true })
    .click();
  await page.getByRole("button", { name: "查询", exact: true }).click();
  await expect(
    page.getByText("本页 1 条 · 每页最多50条", { exact: true }),
  ).toBeVisible();
  expect(fixture.requests.at(-1)?.searchParams.get("status")).toBe("FROZEN");
  await page.getByRole("button", { name: "重置筛选", exact: true }).click();
  await expect(
    page.getByText("本页 21 条 · 每页最多50条", { exact: true }),
  ).toBeVisible();
  fixture.fail();
  await page.getByRole("button", { name: "刷新", exact: true }).click();
  await expect(
    page.getByText("查询服务暂不可用", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "下一页", exact: true }),
  ).toBeDisabled();
  await expect(page.getByText("列表未加载", { exact: true })).toBeVisible();
});

test("紧凑筛选与详情在桌面和手机可阅读，按钮保持统一", async ({ page }) => {
  await members(page);
  const folder =
    process.env.COMMERCE_EVIDENCE_DIR ?? "../.local/frontend-usability";
  mkdirSync(folder, { recursive: true });
  for (const width of [1440, 390, 320]) {
    await page.setViewportSize({ width, height: width === 1440 ? 1000 : 844 });
    await expect(page.getByLabel("关键词", { exact: true })).toBeVisible();
    await assertButtonSizes(page);
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth + 1,
      ),
    ).toBe(true);
    await page.screenshot({
      path: `${folder}/members-${width}.png`,
      fullPage: true,
      animations: "disabled",
    });
  }
  await page
    .locator(".ant-table-tbody tr[data-row-key]")
    .first()
    .getByRole("button", { name: "更多", exact: true })
    .click();
  await page.getByRole("menuitem", { name: "档案记录", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await assertCentered(page, dialog);
  await expect(dialog.getByText("m01", { exact: true }).first()).toBeVisible();
  await dialog.screenshot({
    path: `${folder}/member-details-320.png`,
    animations: "disabled",
  });
});

test("中央订单直接展示经营字段，时间筛选发送真实UTC条件", async ({ page }) => {
  const base = process.env.COMMERCE_CENTRAL_UI_URL;
  test.skip(!base, "需显式独立SSO预览，夹具不证明中央授权");
  const tenant = "11111111-1111-4111-8111-111111111111";
  const requests: URL[] = [];
  const order = {
    orderId: "order-usability",
    memberId: "member-usability",
    storeId: "store-usability",
    merchantId: "merchant-usability",
    status: "PAID",
    paymentKind: "CHANNEL_REQUIRED",
    payable: "12345.67",
    version: 3,
    createdAt: "2026-10-01T12:00:00Z",
    expiresAt: null,
    items: [],
  };
  await page.addInitScript(() => {
    sessionStorage.setItem(
      "oidc.user:http://127.0.0.1:18090:commerce-ui-contract",
      JSON.stringify({
        access_token: "fixture-central",
        token_type: "Bearer",
        scope: "openid profile",
        profile: { sub: "employee" },
        expires_at: Math.floor(Date.now() / 1000) + 1800,
      }),
    );
  });
  await page.route("**/v1/**", (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === "/v1/admin/orders") requests.push(url);
    return route.fulfill({
      json: url.pathname.endsWith("-access")
        ? { allowed: true }
        : url.pathname === "/v1/admin/orders"
          ? [order]
          : order,
    });
  });
  await page.goto(`${base}/operations/orders?tenant_id=${tenant}`);
  await expect(
    page.getByText("member-usability", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("merchant-usability", { exact: true }),
  ).toBeVisible();
  await expect(page.getByText("¥12,345.67", { exact: true })).toBeVisible();
  await expect(page.getByText("已付款", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("渠道支付", { exact: true })).toBeVisible();
  await page.getByLabel("创建起点", { exact: true }).fill("2026-10-01T00:00");
  await page.getByLabel("创建终点", { exact: true }).fill("2026-10-02T00:00");
  await page.getByRole("button", { name: "查询", exact: true }).click();
  const start = await page.evaluate(() =>
    new Date("2026-10-01T00:00").toISOString(),
  );
  await expect
    .poll(() => requests.at(-1)?.searchParams.get("from"))
    .toBe(start);
  await page.reload();
  await expect(page.getByLabel("创建起点", { exact: true })).toHaveValue(
    "2026-10-01T00:00",
  );
  const folder =
    process.env.COMMERCE_EVIDENCE_DIR ?? "../.local/frontend-usability";
  mkdirSync(folder, { recursive: true });
  for (const width of [1440, 390, 320]) {
    await page.setViewportSize({ width, height: 1000 });
    await assertButtonSizes(page);
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth + 1,
      ),
    ).toBe(true);
    await page.screenshot({
      path: `${folder}/central-orders-${width}.png`,
      fullPage: true,
    });
  }
  await page.getByRole("button", { name: "查看详情", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await assertCentered(page, dialog);
  await expect(dialog.getByText("未提供", { exact: true })).toBeVisible();
  await expect(dialog.getByText("¥12,345.67", { exact: true })).toBeVisible();
  await dialog.screenshot({ path: `${folder}/central-order-details-320.png` });
});
