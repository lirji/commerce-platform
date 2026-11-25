import { expect, test, type Page } from "@playwright/test";
import { centralRoutes } from "../src/iam/navigation";
import { centralNavigationFixture } from "./centralNavigationFixture";
import { mkdirSync } from "node:fs";
import { assertButtonSizes, assertCentered } from "./presentation";

async function restoreCentralFixture(page: Page) {
  await page.addInitScript(
    ({ authority, client }) => {
      sessionStorage.setItem(
        `oidc.user:${authority}:${client}`,
        JSON.stringify({
          access_token: "fixture-central",
          token_type: "Bearer",
          scope: "openid profile",
          profile: { sub: "employee" },
          expires_at: Math.floor(Date.now() / 1000) + 1800,
        }),
      );
    },
    {
      authority:
        process.env.COMMERCE_CENTRAL_AUTHORITY ?? "http://127.0.0.1:18090",
      client: process.env.COMMERCE_CENTRAL_CLIENT ?? "commerce-ui-contract",
    },
  );
}

test("售后详情读取当前原单，显示实际门店和商品行", async ({ page }) => {
  const base = process.env.COMMERCE_CENTRAL_UI_URL;
  test.skip(!base, "需独立中央预览");
  const tenant = "11111111-1111-4111-8111-111111111111";
  const past = {
    caseId: "CASE-1",
    orderId: "ORDER-1",
    memberId: "MEMBER-1",
    status: "REQUESTED",
    returnRequired: true,
    refundAmount: "8.00",
    version: 1,
    items: [],
  };
  const current = {
    ...past,
    storeId: "STORE-1",
    createdAt: "2026-10-01T00:00:00Z",
    status: "WAIT_RETURN",
    version: 2,
    items: [{ skuId: "SKU-REAL", quantity: 2, refundAmount: "8.00" }],
  };
  let deny = false;
  await restoreCentralFixture(page);
  await page.route("**/v1/**", (route) => {
    const { pathname } = new URL(route.request().url());
    if (pathname === "/v1/operations/navigation")
      return route.fulfill({ json: centralNavigationFixture(tenant) });
    if (pathname === "/v1/admin/aftersales")
      return route.fulfill({ json: [past] });
    if (pathname === "/v1/admin/aftersales/CASE-1")
      return deny
        ? route.fulfill({ status: 403, json: {} })
        : route.fulfill({ json: current });
    return route.fulfill({ status: 403, json: {} });
  });
  await page.goto(`${base}/operations/aftersales?tenant_id=${tenant}`);
  const detail = page.getByRole("dialog", { name: "业务详情" });
  await page.getByRole("button", { name: "查看详情", exact: true }).click();
  await expect(detail.getByText("待退货入库", { exact: true })).toBeVisible();
  await expect(detail.getByText("STORE-1", { exact: true })).toBeVisible();
  await expect(detail.getByRole("cell", { name: "SKU-REAL" })).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(
    detail.getByRole("columnheader", { name: "退款金额" }),
  ).toBeVisible();
  await expect(detail.getByRole("columnheader", { name: "单价" })).toHaveCount(
    0,
  );
  await expect
    .poll(() =>
      page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth + 1,
      ),
    )
    .toBe(true);
  await page.screenshot({
    path: `${process.env.COMMERCE_EVIDENCE_DIR ?? "../.local"}/current-aftersale-390.png`,
    fullPage: false,
    animations: "disabled",
  });
  await detail.getByRole("button", { name: "返回列表" }).click();
  deny = true;
  await page.getByRole("button", { name: "查看详情", exact: true }).click();
  await expect(detail.getByText(/没有.*权限/).first()).toBeVisible();
  await expect(detail.getByText("SKU-REAL", { exact: true })).toHaveCount(0);
});

test("停止任务按原类型和编号查恢复审计并重置游标", async ({ page }) => {
  const base = process.env.COMMERCE_CENTRAL_UI_URL;
  test.skip(!base, "需独立中央预览");
  const tenant = "11111111-1111-4111-8111-111111111111";
  const audit = {
    id: 3,
    workType: "order.expiry",
    workId: "ORDER-STOP",
    action: "RETRY",
    result: "APPLIED",
    reason: "原失败已核对",
    createdAt: "2026-10-01T00:00:00Z",
  };
  const calls: URL[] = [];
  const writes: string[] = [];
  await restoreCentralFixture(page);
  await page.route("**/v1/**", (route) => {
    const url = new URL(route.request().url());
    calls.push(url);
    if (route.request().method() !== "GET") writes.push(url.pathname);
    if (url.pathname === "/v1/operations/navigation")
      return route.fulfill({ json: centralNavigationFixture(tenant) });
    if (url.pathname.endsWith("/work-types"))
      return route.fulfill({
        json: [{ workType: "order.expiry", actions: ["RETRY"] }],
      });
    if (url.pathname.endsWith("/stopped"))
      return route.fulfill({
        json: [
          {
            workId: audit.workId,
            workType: audit.workType,
            state: "FAILED",
            attempts: 1,
          },
        ],
      });
    if (url.pathname.endsWith("/recoveries"))
      return route.fulfill({
        json: url.searchParams.get("workId") === audit.workId ? [audit] : [],
      });
    return route.fulfill({
      json: url.pathname.endsWith("-access") ? { allowed: false } : [],
    });
  });
  await page.goto(
    `${base}/operations/runtime?tenant_id=${tenant}&CentralRuntime.historyAfter=41`,
  );
  await page.getByRole("combobox", { name: "恢复工作类型" }).click();
  await page.getByText(/订单到期关闭.*重试/).click();
  await page
    .getByRole("button", { name: "查看原工作审计", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "恢复审计", exact: true }),
  ).toBeFocused();
  await expect(page.getByText("原失败已核对", { exact: true })).toBeVisible();
  const target = calls
    .filter((url) => url.pathname.endsWith("/recoveries"))
    .at(-1)!;
  expect(Object.fromEntries(target.searchParams)).toEqual({
    after: "0",
    limit: "50",
    workType: "order.expiry",
    workId: "ORDER-STOP",
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({
    path: `${process.env.COMMERCE_EVIDENCE_DIR ?? "../.local"}/original-work-audit-390.png`,
    animations: "disabled",
  });
  await page.reload();
  await expect(page.getByText("原失败已核对", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "清除原工作筛选" }).click();
  await expect(page.getByText("原失败已核对", { exact: true })).toHaveCount(0);
  calls.length = 0;
  await page.goto(
    `${base}/operations/runtime?tenant_id=${tenant}&CentralRuntime.work=invalid`,
  );
  await expect(
    page.getByText("工作类型或编号无效", { exact: true }),
  ).toBeVisible();
  expect(calls.some((url) => url.pathname.endsWith("/recoveries"))).toBe(false);
  expect(writes).toEqual([]);
});

test("交易列和状态存档按浏览器范围恢复且保留当前精确归属", async ({ page }) => {
  const base = process.env.COMMERCE_CENTRAL_UI_URL;
  test.skip(!base, "需独立中央预览");
  const tenant = "11111111-1111-4111-8111-111111111111";
  const otherTenant = "22222222-2222-4222-8222-222222222222";
  const calls: URL[] = [];
  await restoreCentralFixture(page);
  await page.route("**/v1/**", (route) => {
    const url = new URL(route.request().url());
    calls.push(url);
    if (url.pathname.endsWith("/navigation"))
      return route.fulfill({
        json: centralNavigationFixture(
          route.request().headers()["x-tenant-id"],
        ),
      });
    return route.fulfill({
      json: url.pathname.endsWith("-access") ? { allowed: false } : [],
    });
  });
  const open = (id: string, filter: object, after = "") => {
    const query = new URLSearchParams({
      tenant_id: id,
      "OperationsList./admin/fulfillments.query": JSON.stringify(filter),
      "OperationsWorkspace.after": after,
    });
    return page.goto(`${base}/operations/fulfillments?${query}`);
  };
  await open(tenant, {
    status: "READY",
    blocked: true,
    storeId: "STORE-A",
    orderId: "ORDER-A",
    limit: 25,
  });
  await page.getByRole("button", { name: "保存状态筛选" }).click();
  await page.getByRole("button", { name: "表格列" }).click();
  await page.getByRole("checkbox", { name: "仓配渠道", exact: true }).uncheck();
  await expect(
    page.getByRole("columnheader", { name: "仓配渠道", exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("columnheader", { name: "订单", exact: true }),
  ).toBeVisible();
  await open(tenant, { storeId: "STORE-B", orderId: "ORDER-B" }, "OLD-CURSOR");
  await page.getByRole("button", { name: "应用已保存筛选" }).click();
  await expect
    .poll(() =>
      Object.fromEntries(
        calls.filter((url) => url.pathname === "/v1/admin/fulfillments").at(-1)!
          .searchParams,
      ),
    )
    .toEqual({
      after: "",
      limit: "25",
      status: "READY",
      blocked: "true",
      storeId: "STORE-B",
      orderId: "ORDER-B",
    });
  await expect(
    page.getByRole("columnheader", { name: "仓配渠道", exact: true }),
  ).toHaveCount(0);
  expect(
    await page.evaluate(() =>
      Object.values(localStorage).some((value) =>
        /STORE-A|ORDER-A|STORE-B|ORDER-B/.test(value),
      ),
    ),
  ).toBe(false);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({
    path: `${process.env.COMMERCE_EVIDENCE_DIR ?? "../.local"}/trade-preferences-390.png`,
    fullPage: true,
    animations: "disabled",
  });
  await open(otherTenant, {});
  await expect(
    page.getByRole("button", { name: "保存状态筛选" }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "应用已保存筛选" }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("columnheader", { name: "仓配渠道", exact: true }),
  ).toBeVisible();
});

test("会员档案用实际会员编号打开独立授权行为读取", async ({ page }) => {
  const base = process.env.COMMERCE_CENTRAL_UI_URL;
  test.skip(!base, "需独立中央预览");
  const tenant = "11111111-1111-4111-8111-111111111111";
  const member = {
    memberId: "MEMBER-ORIGINAL",
    actorId: "fixture-customer",
    displayName: "演示会员",
    status: "ACTIVE",
    memberLevel: "BASIC",
    version: 2,
  };
  const calls: { method: string; path: string }[] = [];
  await restoreCentralFixture(page);
  await page.route("**/v1/**", (route) => {
    const path = new URL(route.request().url()).pathname;
    calls.push({ path, method: route.request().method() });
    if (path.endsWith("/navigation"))
      return route.fulfill({ json: centralNavigationFixture(tenant) });
    if (path === "/v1/admin/members") return route.fulfill({ json: [member] });
    if (path === "/v1/admin/member-behavior/MEMBER-ORIGINAL")
      return route.fulfill({ status: 403, json: {} });
    return route.fulfill({
      json: path.endsWith("-access") ? { allowed: false } : [],
    });
  });
  await page.goto(
    `${base}/operations/members?tenant_id=${tenant}&environment=test`,
  );
  await page.getByRole("button", { name: "查看变更记录", exact: true }).click();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({
    path: `${process.env.COMMERCE_EVIDENCE_DIR ?? "../.local"}/member-behavior-link-390.png`,
    animations: "disabled",
  });
  await page.getByRole("link", { name: "查看会员行为", exact: true }).click();
  await expect(page.getByLabel("查询会员编号", { exact: true })).toHaveValue(
    member.memberId,
  );
  await expect(page.getByText(/没有.*权限/).first()).toBeVisible();
  await expect
    .poll(() =>
      calls.some(
        (call) =>
          call.path === "/v1/admin/member-behavior/MEMBER-ORIGINAL/events",
      ),
    )
    .toBe(true);
  expect(calls.every((call) => call.method === "GET")).toBe(true);
  expect(new URL(page.url()).searchParams.get("environment")).toBe("test");
});

test("营销执行用精确订单筛选并按原活动读取权益与事件事实", async ({ page }) => {
  const base = process.env.COMMERCE_CENTRAL_UI_URL;
  test.skip(!base, "需独立中央预览");
  const tenant = "11111111-1111-4111-8111-111111111111";
  const row = {
    orderId: "ORDER-EXACT",
    campaignId: "ACTIVE-1",
    campaignVersion: 2,
    storeId: "STORE-A",
    memberId: "MEMBER-1",
    discountAmount: "8.00",
    status: "GRANT_REQUESTED",
    lockVersion: 1,
  };
  const calls: URL[] = [];
  await restoreCentralFixture(page);
  await page.route("**/v1/**", (route) => {
    const url = new URL(route.request().url());
    calls.push(url);
    if (url.pathname.endsWith("/navigation"))
      return route.fulfill({ json: centralNavigationFixture(tenant) });
    if (url.pathname === "/v1/admin/marketing-executions")
      return route.fulfill({
        json: url.searchParams.get("orderId") === row.orderId ? [row] : [],
      });
    if (url.pathname === "/v1/admin/marketing-executions/ORDER-EXACT/ACTIVE-1")
      return route.fulfill({
        json: {
          ...row,
          status: "GRANT_FAILED",
          grantStatus: "RESERVED",
          eventId: "EV-1",
          eventStatus: "ISOLATED",
          failureClass: "TRANSIENT",
          evaluatedAt: "2026-10-01T00:00:00Z",
          updatedAt: "2026-10-01T00:01:00Z",
        },
      });
    return route.fulfill({
      json: url.pathname.endsWith("-access") ? { allowed: false } : [],
    });
  });
  await page.goto(
    `${base}/operations/marketing-executions?tenant_id=${tenant}`,
  );
  await page.getByLabel("精确订单编号", { exact: true }).fill(row.orderId);
  await page.getByRole("combobox", { name: "最新事件失败分类" }).click();
  await page.getByText("暂时故障", { exact: true }).click();
  await page.getByRole("button", { name: "查询", exact: true }).click();
  await page.getByRole("button", { name: row.orderId, exact: true }).click();
  const fact = page
    .locator(".ant-card")
    .filter({ has: page.getByText("读取指定订单活动参与", { exact: true }) });
  await expect(fact.getByText("权益实际状态", { exact: true })).toBeVisible();
  await expect(fact.getByText("权益发放失败", { exact: true })).toBeVisible();
  await expect(fact.getByText("EV-1", { exact: true })).toBeVisible();
  await expect(fact.getByRole("link", { name: "查看原订单" })).toBeVisible();
  expect(
    calls.some(
      (url) =>
        url.pathname === "/v1/admin/marketing-executions" &&
        url.searchParams.get("orderId") === row.orderId &&
        url.searchParams.get("failureClass") === "TRANSIENT" &&
        url.searchParams.get("after") === "",
    ),
  ).toBe(true);
  await page.setViewportSize({ width: 390, height: 844 });
  await fact.screenshot({
    path: `${process.env.COMMERCE_EVIDENCE_DIR ?? "../.local"}/marketing-execution-390.png`,
    animations: "disabled",
  });
});

test("事件按精确业务编号查询，仅已知订单事件链接原订单", async ({ page }) => {
  const base = process.env.COMMERCE_CENTRAL_UI_URL;
  test.skip(!base, "需独立中央预览");
  const tenant = "11111111-1111-4111-8111-111111111111";
  const aggregateId = "ORDER-ORIGINAL";
  const events = [
    {
      eventId: "EV-ORDER",
      aggregateId,
      eventType: "order.paid.v1",
      status: "PENDING",
      attempts: 0,
    },
    {
      eventId: "EV-OTHER",
      aggregateId,
      eventType: "member.cart.abandoned.v1",
      status: "PENDING",
      attempts: 0,
    },
  ];
  let requested: URL | undefined;
  await restoreCentralFixture(page);
  await page.route("**/v1/**", (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith("/navigation"))
      return route.fulfill({ json: centralNavigationFixture(tenant) });
    if (url.pathname === "/v1/admin/events") {
      requested = url;
      return route.fulfill({
        json: url.searchParams.get("aggregateId") === aggregateId ? events : [],
      });
    }
    return route.fulfill({ status: 403, json: {} });
  });
  await page.goto(`${base}/operations/events?tenant_id=${tenant}`);
  await page.getByLabel("精确业务编号", { exact: true }).fill(aggregateId);
  await page.getByRole("button", { name: "查询", exact: true }).click();
  const row = (id: string) =>
    page
      .getByRole("row")
      .filter({ has: page.getByRole("cell", { name: id, exact: true }) });
  await row("EV-ORDER").getByRole("button", { name: "查看详情" }).click();
  const detail = page.getByRole("dialog", { name: "业务详情" });
  const link = detail.getByRole("link", { name: "查看原订单" });
  const target = new URL((await link.getAttribute("href"))!, page.url());
  expect(
    JSON.parse(target.searchParams.get("OperationsList./admin/orders.query")!),
  ).toEqual({ orderId: aggregateId });
  expect(requested?.searchParams.get("aggregateId")).toBe(aggregateId);
  expect(requested?.searchParams.get("after")).toBe("");
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({
    path: `${process.env.COMMERCE_EVIDENCE_DIR ?? "../.local"}/event-link-390.png`,
    animations: "disabled",
  });
  await detail.getByRole("button", { name: "返回列表" }).click();
  await row("EV-OTHER").getByRole("button", { name: "查看详情" }).click();
  await expect(detail.getByRole("link", { name: "查看原订单" })).toHaveCount(0);
});

test("原订单下钻只打开当前导航允许的精确读取", async ({ page }) => {
  const base = process.env.COMMERCE_CENTRAL_UI_URL;
  test.skip(!base, "需独立中央预览");
  const tenant = "11111111-1111-4111-8111-111111111111";
  const order = {
    orderId: "ORDER-1",
    memberId: "MEMBER-1",
    storeId: "STORE-A",
    status: "PAID",
    payable: "8.00",
  };
  const calls: URL[] = [];
  const writes: string[] = [];
  await restoreCentralFixture(page);
  await page.route("**/v1/**", (route) => {
    const url = new URL(route.request().url());
    calls.push(url);
    if (route.request().method() !== "GET") writes.push(url.pathname);
    if (url.pathname === "/v1/operations/navigation") {
      const nav = centralNavigationFixture(tenant);
      return route.fulfill({
        json: {
          ...nav,
          menus: nav.menus.filter(
            (menu) => menu.route !== "/operations/refunds",
          ),
        },
      });
    }
    if (url.pathname === "/v1/admin/orders")
      return route.fulfill({ json: [order] });
    if (url.pathname === `/v1/admin/orders/${order.orderId}`)
      return route.fulfill({ json: order });
    if (url.pathname === `/v1/admin/orders/${order.orderId}/payment`)
      return route.fulfill({
        json: {
          paymentId: "PAY-1",
          orderId: order.orderId,
          amount: "8.00",
          currency: "CNY",
          provider: "SANDBOX",
          status: "UNKNOWN",
          version: 1,
        },
      });
    return route.fulfill({
      json: url.pathname.endsWith("-access") ? { allowed: false } : [],
    });
  });
  await page.goto(
    `${base}/operations/orders?tenant_id=${tenant}&environment=test`,
  );
  await page.getByRole("button", { name: "查看详情", exact: true }).click();
  const detail = page.getByRole("dialog");
  await expect(detail.getByRole("link", { name: "查看退款" })).toHaveCount(0);
  await expect(detail.getByRole("link", { name: "查看售后" })).toBeVisible();
  await detail.getByRole("link", { name: "查看原支付" }).click();
  await expect(page.getByLabel("订单编号", { exact: true })).toHaveValue(
    order.orderId,
  );
  await expect(page.getByText("PAY-1", { exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "查看退款" })).toHaveCount(0);
  expect(new URL(page.url()).searchParams.get("environment")).toBe("test");
  expect(
    calls
      .filter((x) => x.pathname.includes("/payment"))
      .some((x) => x.pathname === `/v1/admin/orders/${order.orderId}/payment`),
  ).toBe(true);
  expect(writes).toEqual([]);
});

test("异常待办使用独立权限，同门店筛选和有界实数", async ({ page }) => {
  const base = process.env.COMMERCE_CENTRAL_UI_URL;
  test.skip(!base, "需独立中央预览");
  const tenant = "11111111-1111-4111-8111-111111111111";
  const basePath = "/v1/admin/fulfillments";
  const calls: URL[] = [];
  await restoreCentralFixture(page);
  await page.route("**/v1/**", (route) => {
    const url = new URL(route.request().url());
    calls.push(url);
    if (url.pathname === "/v1/operations/navigation") {
      const nav = centralNavigationFixture(tenant);
      return route.fulfill({
        json: {
          ...nav,
          menus: nav.menus.filter(
            (menu) => menu.route !== "/operations/refunds",
          ),
        },
      });
    }
    if (url.pathname === basePath)
      return route.fulfill({
        json: Array.from({ length: 10 }, (_, i) => ({
          orderId: `F-${i}`,
          status: "READY",
          blocked: true,
        })),
      });
    if (url.pathname === "/v1/admin/aftersales")
      return route.fulfill({ status: 403, json: {} });
    if (url.pathname === "/v1/admin/dashboard")
      return route.fulfill({ status: 403, json: {} });
    return route.fulfill({
      json: url.pathname.endsWith("-access") ? { allowed: false } : [],
    });
  });
  await page.goto(
    `${base}/operations/dashboard?tenant_id=${tenant}&environment=test&store_id=STORE-A`,
  );
  const queue = page.getByRole("link", { name: "查看售后阻拦的履约" });
  await expect(queue).toContainText("至少 10 条");
  await expect(
    page.getByRole("link", { name: "查看待确认关闭的订单" }),
  ).toContainText("0 条");
  await expect(page.getByText("当前无队列读取权限")).toBeVisible();
  await expect(page.getByText("结果未知的退款")).toHaveCount(0);
  expect(calls.some((url) => url.pathname === "/v1/admin/refunds")).toBe(false);
  const first = calls.find((url) => url.pathname === basePath)!;
  expect(Object.fromEntries(first.searchParams)).toEqual({
    after: "",
    limit: "10",
    blocked: "true",
    storeId: "STORE-A",
  });
  for (const width of [1440, 1024, 390, 320]) {
    await page.setViewportSize({ width, height: 844 });
    await expect(queue).toBeVisible();
    await page.screenshot({
      path: `${process.env.COMMERCE_EVIDENCE_DIR ?? "../.local"}/exceptions-${width}.png`,
      fullPage: true,
      animations: "disabled",
    });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth > innerWidth + 1,
      ),
    ).toBe(false);
  }
  await queue.focus();
  await expect(queue).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("row").filter({ hasText: "F-0" })).toBeVisible();
  const list = calls.filter((url) => url.pathname === basePath).at(-1)!;
  expect(list.searchParams.get("storeId")).toBe(
    first.searchParams.get("storeId"),
  );
  expect(list.searchParams.get("blocked")).toBe(
    first.searchParams.get("blocked"),
  );
});

// 独立SSO预览只验证壳层与公开DTO，不以夹具证明中央授权或OIDC交换成功。
test("中央页面族统一导航，权限拒绝可见，页签刷新恢复，窄屏不溢出", async ({
  page,
}) => {
  test.setTimeout(180000);
  const base = process.env.COMMERCE_CENTRAL_UI_URL;
  test.skip(!base, "需显式启用独立SSO预览；默认CI不伪装中央登录");
  const tenant = "11111111-1111-4111-8111-111111111111";
  const errors: string[] = [];
  const forbiddenReads: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await restoreCentralFixture(page);
  await page.route("**/v1/**", (route) => {
    const path = new URL(route.request().url()).pathname;
    if (["/v1/me", "/v1/stores", "/v1/runtime-capabilities"].includes(path))
      forbiddenReads.push(path);
    // 此项明确是公开DTO界面夹具；新增导航需提供正式本人契约，不回退全菜单。
    const json =
      path === "/v1/operations/navigation"
        ? centralNavigationFixture(tenant)
        : path.includes("/access") || path.endsWith("-access")
          ? { allowed: false, receive: false, update: false }
          : path === "/v1/operations/scoped/product"
            ? { items: [], stores: 0, total: 0 }
            : path.endsWith("/rule-fields")
              ? {}
              : [];
    return route.fulfill({ json });
  });
  const folder = `${process.env.COMMERCE_EVIDENCE_DIR ?? "../.local/b-console-experience"}/central-screenshots`;
  mkdirSync(folder, { recursive: true });
  for (const path of centralRoutes) {
    await page.goto(`${base}${path}?tenant_id=${tenant}&store_id=store-ui`);
    await expect(page.locator(".central-app")).toBeVisible();
    await expect(page.getByRole("heading", { level: 1 })).toHaveCount(1);
    // 部分旅程页由外层统一处理会话退出；本回归核对各页面实际操作按钮。
    await expect(page.locator(".ant-spin-spinning")).toHaveCount(0);
    await assertButtonSizes(page);
    for (const width of [1440, 390, 320]) {
      await page.setViewportSize({
        width,
        height: width === 1440 ? 1000 : 844,
      });
      await assertButtonSizes(page);
      await page.screenshot({
        path: `${folder}/${path.split("/").slice(1).join("-")}-${width}.png`,
        fullPage: true,
        animations: "disabled",
      });
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth > innerWidth + 1,
        ),
      ).toBe(false);
    }
    await page.setViewportSize({ width: 1440, height: 1000 });
  }
  await page.goto(`${base}/operations/directory?tenant_id=${tenant}`);
  await page.getByRole("tab", { name: "门店目录", exact: true }).click();
  await expect(page).toHaveURL(/workspaceTab=stores/);
  await page.reload();
  await expect(
    page.getByRole("tab", { name: "门店目录", exact: true }),
  ).toHaveAttribute("aria-selected", "true");
  const currentUrl = page.url();
  await page.getByRole("link", { name: "跳到业务内容", exact: true }).focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("main", { name: "业务内容" })).toBeFocused();
  expect(page.url()).toBe(currentUrl);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole("button", { name: "打开经营导航", exact: true }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await assertCentered(page);
  await assertButtonSizes(page);
  await page.screenshot({
    path: `${folder}/directory-navigation-390.png`,
    fullPage: true,
    animations: "disabled",
  });
  await page.locator(".ant-modal-close").click();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth > innerWidth + 1,
    ),
  ).toBe(false);
  let unavailable = true;
  await page.route("**/v1/admin/stores?**", (route) =>
    route.fulfill(
      unavailable
        ? { status: 503, json: { message: "门店目录暂不可用" } }
        : { json: [] },
    ),
  );
  await page.reload();
  await expect(
    page.getByRole("button", { name: "重试加载", exact: true }),
  ).toBeVisible();
  unavailable = false;
  await page.getByRole("button", { name: "重试加载", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "重试加载", exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("tab", { name: "门店目录", exact: true }),
  ).toHaveAttribute("aria-selected", "true");
  await page.route("**/v1/admin/stores?**", (route) =>
    route.fulfill({ status: 403, json: {} }),
  );
  await page.reload();
  await expect(
    page.getByText("当前成员没有此操作或资源的权限", { exact: true }),
  ).toBeVisible();
  await page.screenshot({
    path: `${folder}/directory-forbidden-390.png`,
    fullPage: true,
    animations: "disabled",
  });
  expect(errors).toEqual([]);
  expect(forbiddenReads).toEqual([]);
});

test("中央组织入口按本人菜单落地，运营页面按真实查询分页", async ({ page }) => {
  const base = process.env.COMMERCE_CENTRAL_UI_URL;
  test.skip(!base, "需启用独立中央 UI 预览");
  const tenant = "11111111-1111-4111-8111-111111111111";
  const queries: URLSearchParams[] = [];
  const ids = Array.from(
    { length: 105 },
    (_, index) => `page-${String(index + 1).padStart(3, "0")}`,
  );
  await restoreCentralFixture(page);
  await page.route("**/v1/**", (route) => {
    expect(route.request().method()).toBe("GET");
    const url = new URL(route.request().url());
    if (url.pathname === "/v1/operations/navigation") {
      const fixture = centralNavigationFixture(tenant);
      return route.fulfill({
        json: {
          ...fixture,
          menus: fixture.menus.filter(({ code }) =>
            ["group.platform", "menu.operations.ops-pages"].includes(code),
          ),
        },
      });
    }
    if (url.pathname !== "/v1/admin/ops-pages")
      return route.fulfill({ json: { allowed: false } });
    const params = url.searchParams;
    queries.push(params);
    const limit = Number(params.get("limit"));
    const after = params.get("after") ?? "";
    const data = ids
      .filter((id) => id > after)
      .slice(0, limit)
      .map((pageId) => ({
        content: {
          pageId,
          version: 1,
          storeId: "store-ui",
          title: `运营页面 ${pageId}`,
          sections: [],
          actions: [],
        },
        status: "DRAFT",
        lockVersion: 1,
      }));
    return route.fulfill({ json: data });
  });
  await page.goto(`${base}/operations?tenant_id=${tenant}`);
  await expect(page).toHaveURL(/\/operations\/ops-pages\?tenant_id=/);
  await page.getByLabel("关键词", { exact: true }).fill("运营页面");
  await page.getByLabel("状态", { exact: true }).click();
  await page
    .locator(".ant-select-item-option-content")
    .getByText("草稿", { exact: true })
    .click();
  await page.getByLabel("每页", { exact: true }).click();
  await page
    .locator(".ant-select-item-option-content")
    .getByText("100条", { exact: true })
    .click();
  await page.getByRole("button", { name: "查询", exact: true }).click();
  await expect(
    page.getByText("运营页面 page-100", { exact: true }),
  ).toBeVisible();
  expect(Object.fromEntries(queries.at(-1) ?? [])).toEqual({
    after: "",
    q: "运营页面",
    status: "DRAFT",
    limit: "100",
  });
  await page.getByRole("button", { name: "下一批", exact: true }).click();
  await expect(
    page.getByText("运营页面 page-101", { exact: true }),
  ).toBeVisible();
  await page.reload();
  await expect(
    page.getByText("运营页面 page-101", { exact: true }),
  ).toBeVisible();
  expect(queries.at(-1)?.get("after")).toBe("page-100");
  await page.getByRole("button", { name: "重置筛选", exact: true }).click();
  await expect(
    page.getByText("运营页面 page-001", { exact: true }),
  ).toBeVisible();
  expect(Object.fromEntries(queries.at(-1) ?? [])).toEqual({
    after: "",
    limit: "50",
  });
});

test("中央商家与门店目录分别恢复查询，旧书签只恢复到原目录", async ({
  page,
}) => {
  const base = process.env.COMMERCE_CENTRAL_UI_URL;
  test.skip(!base, "需启用独立中央 UI 预览");
  const tenant = "11111111-1111-4111-8111-111111111111";
  const keyword = new Map<string, string | null>();
  await restoreCentralFixture(page);
  await page.route("**/v1/**", (route) => {
    expect(route.request().method()).toBe("GET");
    const url = new URL(route.request().url());
    if (url.pathname === "/v1/operations/navigation")
      return route.fulfill({ json: centralNavigationFixture(tenant) });
    if (["/v1/admin/merchants", "/v1/admin/stores"].includes(url.pathname)) {
      keyword.set(url.pathname, url.searchParams.get("q"));
      return route.fulfill({ json: [] });
    }
    return route.fulfill({ json: { allowed: false } });
  });
  const params = new URLSearchParams({
    tenant_id: tenant,
    workspaceTab: "stores",
  });
  params.set("DirectorySection.query", JSON.stringify({ q: "旧门店" }));
  await page.goto(`${base}/operations/directory?${params}`);
  const panel = page.getByRole("tabpanel");
  const input = panel.getByLabel("关键词", { exact: true });
  await expect(input).toHaveValue("旧门店");
  await expect.poll(() => keyword.get("/v1/admin/stores")).toBe("旧门店");
  expect(new URL(page.url()).searchParams.has("DirectorySection.query")).toBe(
    false,
  );
  await page.getByRole("tab", { name: "商家目录", exact: true }).click();
  await expect(input).toHaveValue("");
  await input.fill("新商家");
  await panel.getByRole("button", { name: "查询", exact: true }).click();
  await expect.poll(() => keyword.get("/v1/admin/merchants")).toBe("新商家");
  await page.getByRole("tab", { name: "门店目录", exact: true }).click();
  await page.reload();
  await expect(input).toHaveValue("旧门店");
  await panel.getByRole("button", { name: "重置筛选", exact: true }).click();
  await expect.poll(() => keyword.get("/v1/admin/stores")).toBe(null);
  await page.getByRole("tab", { name: "商家目录", exact: true }).click();
  await expect(input).toHaveValue("新商家");
});
