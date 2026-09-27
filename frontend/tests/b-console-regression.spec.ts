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

async function openAdmin(page: Page, skuRows: Sku[]) {
  await page.route("**/v1/**", async (route) => {
    const url = new URL(route.request().url());
    const path = url.pathname.slice(3);
    let body: unknown = [];
    if (path === "/me") body = { tenantId: "review-tenant", actorId: "admin", role: "ADMIN" };
    else if (path === "/stores") body = [{ storeId: "store-1", merchantId: "merchant-1", name: "测试店铺", status: "ACTIVE", version: 1 }];
    else if (path === "/runtime-capabilities") body = { sandboxEnabled: false, workersEnabled: false };
    else if (path === "/admin/dashboard") body = null;
    else if (path === "/operations/catalog-categories") body = [];
    else if (path === "/operations/products") body = [];
    else if (path === "/operations/catalog-search") {
      const after = url.searchParams.get("after");
      const query = url.searchParams.get("q");
      body = after === "sku-50" ? [skuRows[50]] : query ? [skuRows[1]] : skuRows.slice(0, 50);
    } else if (path === "/operations/skus/sku-01" && route.request().method() === "POST") {
      const input = route.request().postDataJSON() as { unitPrice: string };
      skuRows[0] = { ...skuRows[0], unitPrice: input.unitPrice, revision: skuRows[0].revision + 1 };
      body = skuRows[0];
    } else if (path === "/admin/journeys") {
      const after = url.searchParams.get("after");
      const ids = after === "journey-50" ? [51] : Array.from({ length: 50 }, (_, i) => i + 1);
      body = ids.map((n) => ({
        content: { journeyId: `journey-${String(n).padStart(2, "0")}`, name: `旅程 ${n}`, version: 1, trigger: "MANUAL", nodes: [] },
        status: "DRAFT", lockVersion: 1,
      }));
    } else if (path === "/admin/campaigns") {
      const ids = url.searchParams.get("after") === "campaign-50" ? [51] : Array.from({ length: 50 }, (_, i) => i + 1);
      body = ids.map((n) => ({
        content: { campaignId: `campaign-${String(n).padStart(2, "0")}`, name: `活动 ${n}`, storeId: "store-1", version: 1, discountAmount: "1.00" },
        status: "DRAFT", lockVersion: 1,
      }));
    } else if (path === "/admin/rules") {
      const ids = url.searchParams.get("after") === "rule-50" ? [51] : Array.from({ length: 50 }, (_, i) => i + 1);
      body = ids.map((n) => ({
        content: { ruleId: `rule-${String(n).padStart(2, "0")}`, name: `规则 ${n}`, version: 1, rule: { kind: "COMPARE", field: "memberLevel", operator: "EQ", valueType: "TEXT", value: "BASIC" } },
        status: "DRAFT", lockVersion: 1,
      }));
    } else if (path === "/admin/ops-pages") {
      const ids = url.searchParams.get("after") === "page-50" ? [51] : Array.from({ length: 50 }, (_, i) => i + 1);
      body = ids.map((n) => ({
        content: { pageId: `page-${String(n).padStart(2, "0")}`, title: `页面 ${n}`, storeId: "store-1", version: 1, sections: [], actions: [] },
        status: "DRAFT", lockVersion: 1,
      }));
    }
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
  });
  await page.goto("/");
  await page.getByLabel("访问凭据", { exact: true }).fill("test-token");
  await page.getByRole("button", { name: "进入平台", exact: true }).click();
  await expect(page.getByRole("button", { name: "退出", exact: true })).toBeVisible();
  await expect.poll(() => page.evaluate(() => location.hash)).toContain("store=store-1");
}

test("商品经营切页和筛选后不保留旧批量选择，刷新后编辑显示新版本", async ({ page }) => {
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
  await page.evaluate(() => { location.hash = "skus?store=store-1"; });
  const first = page.getByRole("row").filter({ hasText: "sku-01" });
  await expect(first).toBeVisible();
  await first.getByRole("checkbox").check();
  await expect(page.getByRole("button", { name: "创建批量计划" })).toBeEnabled();
  await page.getByRole("button", { name: "下一页", exact: true }).click();
  await expect(page.getByRole("row").filter({ hasText: "sku-51" })).toBeVisible();
  await expect(page.getByRole("button", { name: "创建批量计划" })).toBeDisabled();

  await page.getByRole("button", { name: "回到首页" }).first().click();
  await first.getByRole("checkbox").check();
  await page.getByLabel("商品名称或条码").fill("special");
  await page.getByRole("button", { name: "筛选商品" }).click();
  await expect(page.getByRole("row").filter({ hasText: "sku-02" })).toBeVisible();
  await expect(page.getByRole("button", { name: "创建批量计划" })).toBeDisabled();

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
  await page.evaluate(() => { location.hash = "journeys?store=store-1"; });
  await expect(page.getByRole("row").filter({ hasText: "journey-50" })).toBeVisible();
  await page.getByRole("button", { name: "下一页", exact: true }).click();
  await expect(page.getByRole("row").filter({ hasText: "journey-51" })).toBeVisible();
});

test("活动、规则和运营页面可以翻页，运营页面新建不沿用上一草稿", async ({ page }) => {
  await openAdmin(page, []);
  await page.evaluate(() => { location.hash = "campaigns?store=store-1"; });
  await expect(page.getByRole("row").filter({ hasText: "campaign-50" })).toBeVisible();
  await page.getByRole("button", { name: "下一页", exact: true }).click();
  await expect(page.getByRole("row").filter({ hasText: "campaign-51" })).toBeVisible();

  await page.evaluate(() => { location.hash = "rules?store=store-1"; });
  await expect(page.getByRole("row").filter({ hasText: "rule-50" })).toBeVisible();
  await page.getByRole("button", { name: "下一页", exact: true }).click();
  await expect(page.getByRole("row").filter({ hasText: "rule-51" })).toBeVisible();

  await page.evaluate(() => { location.hash = "pages?store=store-1"; });
  await expect(page.getByRole("row").filter({ hasText: "页面 50" })).toBeVisible();
  await page.getByRole("button", { name: "下一页", exact: true }).click();
  const row = page.getByRole("row").filter({ hasText: "页面 51" });
  await expect(row).toBeVisible();
  await row.getByRole("button", { name: "创建新版本" }).click();
  await expect(page.getByLabel("页面标题")).toHaveValue("页面 51");
  await page.getByRole("dialog", { name: "页面编排" }).getByRole("button", { name: "关闭" }).click();
  await page.getByRole("button", { name: "创建运营页面" }).click();
  await expect(page.getByLabel("页面标题")).toHaveValue("");
});
