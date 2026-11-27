import { expect, test, type Page } from "@playwright/test";

type Sku = {
  skuId: string;
  storeId: string;
  title: string;
  unitPrice: string;
  revision: number;
  status: string;
  specifications: { name: string; value: string }[];
};

async function openAdmin(page: Page, skuRows: Sku[], routeOrders = false) {
  await page.route("**/v1/**", async (route) => {
    const url = new URL(route.request().url());
    const path = url.pathname.slice(3);
    if (routeOrders && path === "/admin/orders") return route.fallback();
    let body: unknown = [];
    if (path === "/me")
      body = { tenantId: "review-tenant", actorId: "admin", role: "ADMIN" };
    else if (path === "/stores")
      body = [
        {
          storeId: "store-1",
          merchantId: "merchant-1",
          name: "测试店铺",
          status: "ACTIVE",
          version: 1,
        },
      ];
    else if (path === "/runtime-capabilities")
      body = { sandboxEnabled: false, workersEnabled: false };
    else if (path === "/admin/dashboard") body = null;
    else if (path === "/operations/catalog-categories") body = [];
    else if (path === "/operations/products") body = [];
    else if (path === "/operations/catalog-search") {
      const after = url.searchParams.get("after");
      const query = url.searchParams.get("q");
      body =
        after === "sku-50"
          ? [skuRows[50]]
          : query
            ? [skuRows[1]]
            : skuRows.slice(0, 50);
    } else if (
      path === "/operations/skus/sku-01" &&
      route.request().method() === "POST"
    ) {
      const input = route.request().postDataJSON() as { unitPrice: string };
      skuRows[0] = {
        ...skuRows[0],
        unitPrice: input.unitPrice,
        revision: skuRows[0].revision + 1,
      };
      body = skuRows[0];
    } else if (path === "/admin/journeys") {
      const after = url.searchParams.get("after");
      const ids =
        after === "journey-50"
          ? [51]
          : Array.from({ length: 50 }, (_, i) => i + 1);
      body = ids.map((n) => ({
        content: {
          journeyId: `journey-${String(n).padStart(2, "0")}`,
          name: `旅程 ${n}`,
          version: 1,
          trigger: "MANUAL",
          nodes: [],
        },
        status: "DRAFT",
        lockVersion: 1,
      }));
    } else if (path === "/admin/campaigns") {
      const ids =
        url.searchParams.get("after") === "campaign-50"
          ? [51]
          : Array.from({ length: 50 }, (_, i) => i + 1);
      body = ids.map((n) => ({
        content: {
          campaignId: `campaign-${String(n).padStart(2, "0")}`,
          name: `活动 ${n}`,
          storeId: "store-1",
          version: 1,
          discountAmount: "1.00",
        },
        status: "DRAFT",
        lockVersion: 1,
      }));
    } else if (path === "/admin/rules") {
      const ids =
        url.searchParams.get("after") === "rule-50"
          ? [51]
          : Array.from({ length: 50 }, (_, i) => i + 1);
      body = ids.map((n) => ({
        content: {
          ruleId: `rule-${String(n).padStart(2, "0")}`,
          name: `规则 ${n}`,
          version: 1,
          rule: {
            kind: "COMPARE",
            field: "memberLevel",
            operator: "EQ",
            valueType: "TEXT",
            value: "BASIC",
          },
        },
        status: "DRAFT",
        lockVersion: 1,
      }));
    } else if (path === "/admin/ops-pages") {
      const ids =
        url.searchParams.get("after") === "page-50"
          ? [51]
          : Array.from({ length: 50 }, (_, i) => i + 1);
      body = ids.map((n) => ({
        content: {
          pageId: `page-${String(n).padStart(2, "0")}`,
          title: `页面 ${n}`,
          storeId: "store-1",
          version: 1,
          sections: [],
          actions: [],
        },
        status: "DRAFT",
        lockVersion: 1,
      }));
    }
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(body),
    });
  });
  await page.goto("/");
  await page.getByLabel("访问凭据", { exact: true }).fill("test-token");
  await page.getByRole("button", { name: "进入平台", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "退出", exact: true }),
  ).toBeVisible();
  await expect
    .poll(() => page.evaluate(() => location.hash))
    .toContain("store=store-1");
}

test("商品经营切页和筛选后不保留旧批量选择，刷新后编辑显示新版本", async ({
  page,
}) => {
  const skus: Sku[] = Array.from({ length: 51 }, (_, i) => ({
    skuId: `sku-${String(i + 1).padStart(2, "0")}`,
    storeId: "store-1",
    title: `商品 ${i + 1}`,
    unitPrice: "10.00",
    revision: 1,
    status: "ACTIVE",
    specifications: [],
  }));
  await openAdmin(page, skus);
  await page.evaluate(() => {
    location.hash = "skus?store=store-1";
  });
  const first = page.getByRole("row").filter({ hasText: "sku-01" });
  await expect(first).toBeVisible();
  await first.getByRole("checkbox").check();
  await expect(
    page.getByRole("button", { name: "创建批量计划" }),
  ).toBeEnabled();
  await page.getByRole("button", { name: "下一页", exact: true }).click();
  await expect(
    page.getByRole("row").filter({ hasText: "sku-51" }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "创建批量计划" }),
  ).toBeDisabled();

  await page.getByRole("button", { name: "回到首页" }).first().click();
  await first.getByRole("checkbox").check();
  await page.getByLabel("商品名称或条码").fill("special");
  await page.getByRole("button", { name: "筛选商品" }).click();
  await expect(
    page.getByRole("row").filter({ hasText: "sku-02" }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "创建批量计划" }),
  ).toBeDisabled();

  await page.getByLabel("商品名称或条码").fill("");
  await page.getByRole("button", { name: "筛选商品" }).click();
  await expect(first).toBeVisible();
  await first.getByRole("button", { name: "调价 / 上下架" }).click();
  await page.getByLabel("售价（元）").fill("27.00");
  await page.getByLabel("修订原因").fill("定向回归验证");
  await page.getByRole("button", { name: "确认提交" }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(first).toContainText("¥27.00");
  await first.getByRole("button", { name: "调价 / 上下架" }).click();
  await expect(page.getByLabel("售价（元）")).toHaveValue("27.00");
});

test("旅程超过五十条时可以访问下一页", async ({ page }) => {
  await openAdmin(page, []);
  await page.evaluate(() => {
    location.hash = "journeys?store=store-1";
  });
  await expect(
    page.getByRole("row").filter({ hasText: "journey-50" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "下一页", exact: true }).click();
  await expect(
    page.getByRole("row").filter({ hasText: "journey-51" }),
  ).toBeVisible();
});

test("活动、规则和运营页面可以翻页，运营页面新建不沿用上一草稿", async ({
  page,
}) => {
  await openAdmin(page, []);
  await page.evaluate(() => {
    location.hash = "campaigns?store=store-1";
  });
  await expect(
    page.getByRole("row").filter({ hasText: "campaign-50" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "下一页", exact: true }).click();
  await expect(
    page.getByRole("row").filter({ hasText: "campaign-51" }),
  ).toBeVisible();

  await page.evaluate(() => {
    location.hash = "rules?store=store-1";
  });
  await expect(
    page.getByRole("row").filter({ hasText: "rule-50" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "下一页", exact: true }).click();
  await expect(
    page.getByRole("row").filter({ hasText: "rule-51" }),
  ).toBeVisible();

  await page.evaluate(() => {
    location.hash = "pages?store=store-1";
  });
  await expect(
    page.getByRole("row").filter({ hasText: "页面 50" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "下一页", exact: true }).click();
  const row = page.getByRole("row").filter({ hasText: "页面 51" });
  await expect(row).toBeVisible();
  await row.getByRole("button", { name: "创建新版本" }).click();
  await expect(page.getByLabel("页面标题")).toHaveValue("页面 51");
  await page
    .getByRole("dialog", { name: "页面编排" })
    .getByRole("button", { name: "关闭" })
    .click();
  await page.getByRole("button", { name: "创建运营页面" }).click();
  await expect(page.getByLabel("页面标题")).toHaveValue("");
});

test("规格表格列、行距、全屏与编号复制保留本页批选", async ({
  page,
  context,
}) => {
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  await openAdmin(page, [
    {
      skuId: "sku-copy-01",
      storeId: "store-1",
      title: "批选表格商品",
      unitPrice: "10.00",
      revision: 1,
      status: "ACTIVE",
      specifications: [],
    },
  ]);
  await page.evaluate(() => {
    location.hash = "skus?store=store-1";
  });
  const row = page.getByRole("row").filter({ hasText: "批选表格商品" });
  await expect(row).toBeVisible();
  await row.getByRole("checkbox").check();
  await expect(
    page.getByRole("status").filter({ hasText: "已选 1 个规格" }),
  ).toBeVisible();
  await row
    .getByRole("cell", { name: /批选表格商品/ })
    .getByRole("button", { name: "复制编号" })
    .click();
  await expect
    .poll(() => page.evaluate(() => navigator.clipboard.readText()))
    .toBe("sku-copy-01");

  await page.getByRole("button", { name: "设置表格列", exact: true }).click();
  await page.getByRole("checkbox", { name: "售价", exact: true }).uncheck();
  await expect(
    page.getByRole("columnheader", { name: "售价", exact: true }),
  ).toHaveCount(0);
  await page.getByRole("button", { name: "设置表格列", exact: true }).click();
  await page.getByRole("button", { name: "调整表格密度", exact: true }).click();
  await page.getByRole("radio", { name: "紧凑" }).check();
  await expect(page.locator(".business-table-compact")).toBeVisible();
  await page.getByRole("button", { name: "调整表格密度", exact: true }).click();

  const expand = page.getByRole("button", { name: "全屏查看", exact: true });
  await expand.click();
  const dialog = page.getByRole("dialog", {
    name: "表格全屏查看",
    exact: true,
  });
  await expect(
    dialog
      .getByRole("row")
      .filter({ hasText: "批选表格商品" })
      .getByRole("checkbox"),
  ).toBeChecked();
  await page.keyboard.press("Escape");
  await expect(dialog).toHaveCount(0);
  await expect(expand).toBeFocused();
  await page.reload();
  await expect(
    page.getByRole("row").filter({ hasText: "批选表格商品" }),
  ).toBeVisible();
  await expect(page.locator(".business-table-compact")).toBeVisible();
  await expect(
    page.getByRole("columnheader", { name: "售价", exact: true }),
  ).toHaveCount(0);
});

test("订单快筛和近7天仅向服务端提交实际条件，游标跳转保留适用范围", async ({
  page,
}) => {
  const requests: URL[] = [];
  await page.route(/\/v1\/admin\/orders\?/, (route) => {
    const url = new URL(route.request().url());
    requests.push(url);
    const after = Number(url.searchParams.get("after") ?? 0);
    const limit = Number(url.searchParams.get("limit"));
    return route.fulfill({
      json: Array.from({ length: Math.min(limit, 23 - after) }, (_, index) => ({
        orderId: String(after + index + 1),
        businessNo: `TEST-ORDER-${after + index + 1}`,
        storeId: "store-1",
        status: "PENDING_PAYMENT",
        payable: "12.50",
        createdAt: "2026-10-01T10:00:00Z",
      })),
    });
  });
  await openAdmin(page, [], true);
  await page.evaluate(() => {
    location.hash = "orders";
  });
  const waitOrders = (search: (url: URLSearchParams) => boolean) =>
    page.waitForResponse((response) => {
      const url = new URL(response.url());
      return url.pathname === "/v1/admin/orders" && search(url.searchParams);
    });
  const pending = waitOrders(
    (query) => query.get("status") === "PENDING_PAYMENT",
  );
  const states = page.getByRole("group", { name: "状态快捷筛选" });
  await states.getByRole("button", { name: "待付款", exact: true }).click();
  await pending;
  await expect(
    states.getByRole("button", { name: "待付款", exact: true }),
  ).toHaveAttribute("aria-pressed", "true");

  const filter = page.locator(".query-panel");
  await filter.getByRole("combobox", { name: "快捷时间" }).click();
  await page.getByRole("option", { name: "近7天", exact: true }).click();
  await expect(filter.getByLabel(/创建起点/)).not.toHaveValue("");
  await expect(filter.getByText("筛选已修改，点击查询后生效")).toBeVisible();
  await filter.getByRole("combobox", { name: /每页/ }).click();
  await page.getByRole("option", { name: "10条", exact: true }).click();

  const applied = waitOrders(
    (query) =>
      query.get("limit") === "10" && query.has("from") && query.has("to"),
  );
  await filter.getByRole("button", { name: "查询", exact: true }).click();
  const response = await applied;
  const selectedRange = new URL(response.url()).searchParams;
  expect(
    Date.parse(selectedRange.get("to")!) -
      Date.parse(selectedRange.get("from")!),
  ).toBeGreaterThanOrEqual(6 * 86_400_000);

  const next = page.getByRole("button", { name: "下一页", exact: true });
  await next.click();
  await expect(page.locator(".cursor-position")).toHaveText("第2页");
  await next.click();
  await expect(page.locator(".cursor-position")).toHaveText("第3页");
  await expect(page.locator(".list-count")).toContainText("本页 3 条");
  await page.getByRole("combobox", { name: "跳转已访问页" }).click();
  await page.getByRole("option", { name: "第 1 页", exact: true }).click();
  await expect(page.locator(".cursor-position")).toHaveText("第1页");
  await expect(page.locator(".list-count")).toContainText("本页 10 条");
  await page.getByRole("combobox", { name: "跳转已访问页" }).click();
  await page.getByRole("option", { name: "第 3 页", exact: true }).click();
  await expect(page.locator(".cursor-position")).toHaveText("第3页");
  await expect(page.locator(".list-count")).toContainText("本页 3 条");
  expect(requests.at(-1)?.searchParams.get("after")).toBe("20");
  expect(requests.at(-1)?.searchParams.get("status")).toBe("PENDING_PAYMENT");
  await filter.getByRole("button", { name: "重置筛选", exact: true }).click();
  await expect(filter.getByLabel(/创建起点/)).toHaveValue("");
  await expect(
    page.getByRole("combobox", { name: "跳转已访问页" }),
  ).toHaveCount(0);
  await expect(
    states.getByRole("button", { name: "全部状态", exact: true }),
  ).toHaveAttribute("aria-pressed", "true");
});
