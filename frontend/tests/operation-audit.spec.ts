import { expect, test } from "@playwright/test";
import { randomUUID } from "node:crypto";
import { navigate } from "./navigation";

const token = process.env.COMMERCE_AUDIT_E2E_TOKEN;
const evidence = process.env.COMMERCE_EVIDENCE_DIR;
const adminToken = process.env.COMMERCE_AUDIT_E2E_ADMIN;

test("操作日志只用服务端游标跳转已访问页，修改容量后从首页读取", async ({
  page,
}) => {
  await page.route("**/v1/**", (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === "/v1/me")
      return route.fulfill({
        json: {
          tenantId: "audit-tenant",
          actorId: "auditor",
          role: "PLATFORM_OPERATOR",
        },
      });
    if (url.pathname === "/v1/runtime-capabilities")
      return route.fulfill({
        json: { sandboxEnabled: false, workersEnabled: false },
      });
    if (url.pathname !== "/v1/platform/operation-audits")
      return route.fulfill({ json: [] });
    const cursor = url.searchParams.get("cursor");
    const pageIndex = cursor === "third" ? 3 : cursor === "second" ? 2 : 1;
    return route.fulfill({
      json: {
        items: [
          {
            id: String(pageIndex),
            resourceId: `AUDIT_${pageIndex}`,
            action: "platform.category.create",
            actorId: "auditor",
            result: "SUCCEEDED",
            occurredAt: "2026-11-27T00:00:00Z",
            changes: [],
          },
        ],
        nextCursor:
          pageIndex === 1 ? "second" : pageIndex === 2 ? "third" : undefined,
      },
    });
  });
  await page.goto("/#platform-operation-audits");
  await page.getByLabel("访问凭据", { exact: true }).fill("audit-ui-fixture");
  await page.getByRole("button", { name: "进入平台", exact: true }).click();
  const next = page.getByRole("button", { name: "下一页", exact: true });
  const jump = page.getByRole("combobox", { name: "跳转已访问日志页" });
  await expect(page.getByText("AUDIT_1")).toBeVisible();
  await next.click();
  await expect(page.getByText("AUDIT_2")).toBeVisible();
  await next.click();
  await expect(page.getByText("AUDIT_3")).toBeVisible();
  await jump.click();
  await page.getByRole("option", { name: "第 1 页" }).click();
  await expect(page.getByText("AUDIT_1")).toBeVisible();
  await jump.click();
  await page.getByRole("option", { name: "第 3 页" }).click();
  await expect(page.getByText("AUDIT_3")).toBeVisible();
  await page.getByRole("combobox", { name: "操作日志每页条数" }).click();
  const read = page.waitForRequest((request) => {
    const url = new URL(request.url());
    return (
      url.pathname === "/v1/platform/operation-audits" &&
      url.searchParams.get("limit") === "25" &&
      !url.searchParams.has("cursor")
    );
  });
  await page.getByRole("option", { name: "每页 25 条" }).click();
  await read;
  await expect(page.getByText("AUDIT_1")).toBeVisible();
  await expect(jump).toHaveCount(0);
  await page.reload();
  await expect(page.locator(".pager-navigation")).toContainText("每页 25 条");
});

test("类目停用可在操作日志查看并回到原类目", async ({ page, request }) => {
  test.skip(!token, "需要隔离数据库签发的类目专岗凭据");
  const code = "OA_UI_" + randomUUID().replaceAll("-", "").toUpperCase();
  const created = await request.post("/v1/platform/commerce-categories", {
    headers: {
      Authorization: `Bearer ${token}`,
      "Idempotency-Key": randomUUID(),
    },
    data: {
      code,
      name: "操作审计浏览器验收 " + code.slice(-8),
      kind: "BRANCH",
      sortOrder: 10,
      reason: "准备验收类目",
    },
  });
  expect(created.ok(), await created.text()).toBeTruthy();
  await page.goto("/#platform-categories?category=" + code);
  await page.getByLabel("访问凭据", { exact: true }).fill(token!);
  await page.getByRole("button", { name: "进入平台", exact: true }).click();

  const category = page.getByRole("row", { name: new RegExp(code) });
  await category.getByRole("button", { name: "维护", exact: true }).click();
  const edit = page.getByRole("dialog", { name: "维护", exact: true });
  await edit.getByRole("switch").click();
  await edit.getByLabel("变更原因").fill("浏览器核对停用证据");
  await edit.getByRole("button", { name: "确认提交", exact: true }).click();
  await expect(
    category.getByRole("cell", { name: "已停用", exact: true }),
  ).toBeVisible();

  await page.getByRole("menuitem", { name: "操作日志", exact: true }).click();
  await page.getByLabel("对象编号").fill(code);
  const [filtered] = await Promise.all([
    page.waitForResponse(
      (response) =>
        new URL(response.url()).searchParams.get("resourceId") === code,
    ),
    page.getByRole("button", { name: "查询", exact: true }).click(),
  ]);
  expect(filtered.ok()).toBeTruthy();
  const audit = page
    .getByRole("row", { name: new RegExp(code) })
    .filter({ hasText: "停用类目" });
  await expect(audit.getByText("成功", { exact: true })).toBeVisible();
  if (evidence)
    await page.screenshot({ path: evidence + "/oa01-audit-desktop.png" });

  await audit.getByRole("button", { name: "查看详情", exact: true }).click();
  const details = page.getByRole("dialog", {
    name: /^操作日志详情/,
  });
  await expect(details.getByText("浏览器核对停用证据")).toBeVisible();
  await expect(
    details.getByRole("cell", { name: "启停状态", exact: true }),
  ).toBeVisible();
  await expect(
    details.getByRole("cell", { name: "停用", exact: true }),
  ).toBeVisible();
  if (evidence)
    await page.screenshot({ path: evidence + "/oa01-detail-desktop.png" });

  await page.setViewportSize({ width: 390, height: 844 });
  await expect(
    details.getByRole("button", { name: "查看类目", exact: true }),
  ).toBeVisible();
  if (evidence)
    await page.screenshot({ path: evidence + "/oa01-detail-narrow.png" });
  await details.getByRole("button", { name: "查看类目", exact: true }).click();
  await expect(page.getByLabel("按编码或名称查找类目")).toHaveValue(code);
  await expect(
    category.getByRole("cell", { name: "已停用", exact: true }),
  ).toBeVisible();
});

test("开店资料显示本店草稿和补正记录", async ({ page, request }) => {
  test.skip(!adminToken, "需要隔离数据库签发的店铺运营凭据");
  const suffix = randomUUID().slice(0, 8);
  const storeId = "oa-store-" + suffix;
  const merchantId = "oa-merchant-" + suffix;
  const post = async (route: string, data: unknown) => {
    const response = await request.post(route, {
      headers: {
        Authorization: `Bearer ${adminToken}`,
        "Idempotency-Key": randomUUID(),
      },
      data,
    });
    expect(response.ok(), await response.text()).toBeTruthy();
  };
  await post("/v1/admin/merchants", { merchantId, name: "审计验收商家" });
  await post("/v1/admin/store-openings", {
    storeId,
    merchantId,
    name: "审计验收店铺",
    subjectType: "INDIVIDUAL",
    categoryCode: "HOME_STORAGE",
  });
  await post(`/v1/admin/store-openings/${storeId}/revise`, {
    expectedVersion: 0,
    name: "审计验收店铺补正",
    subjectType: "ENTERPRISE",
    categoryCode: "HOME_STORAGE",
  });

  await page.goto("/");
  await page.getByLabel("访问凭据", { exact: true }).fill(adminToken!);
  await page.getByRole("button", { name: "进入平台", exact: true }).click();
  await navigate(page, "店铺管理");
  const store = page.getByRole("row", { name: new RegExp(storeId) });
  await store.getByRole("button", { name: "继续开店", exact: true }).click();
  const detail = page.getByRole("dialog", { name: "开店资料", exact: true });
  await detail
    .getByRole("button", { name: "查看开店操作记录", exact: true })
    .click();
  await expect(
    detail.getByRole("cell", { name: "保存草稿", exact: true }),
  ).toBeVisible();
  await expect(
    detail.getByRole("cell", { name: "修改开店资料", exact: true }),
  ).toBeVisible();
  const revised = detail.getByRole("row").filter({ hasText: "修改开店资料" });
  await revised.getByRole("button").first().click();
  await expect(
    detail.getByRole("cell", { name: "企业", exact: true }),
  ).toBeVisible();
  if (evidence)
    await page.screenshot({
      path: evidence + "/oa02-opening-audit-desktop.png",
    });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(
    detail.getByRole("cell", { name: "企业", exact: true }),
  ).toBeVisible();
  if (evidence)
    await page.screenshot({
      path: evidence + "/oa02-opening-audit-narrow.png",
    });
});

test("商品类目可查询关联与解绑记录", async ({ page, request }) => {
  test.skip(!adminToken, "需要隔离数据库签发的商品经营凭据");
  const suffix = randomUUID().slice(0, 8);
  const storeId = "catalog-oa-" + suffix;
  const merchantId = "merchant-oa-" + suffix;
  const categoryId = "nav-oa-" + suffix;
  const post = async (path: string, data: unknown) => {
    const response = await request.post(path, {
      headers: {
        Authorization: `Bearer ${adminToken}`,
        "Idempotency-Key": randomUUID(),
      },
      data,
    });
    expect(response.ok(), await response.text()).toBeTruthy();
  };
  await post("/v1/admin/merchants", { merchantId, name: "绑定审计商家" });
  await post("/v1/admin/stores", { merchantId, storeId, name: "绑定审计店铺" });
  const categories = "/v1/operations/catalog-categories";
  await post(categories, {
    storeId,
    categoryId,
    name: "收纳导航",
    platformCategoryCode: "HOME_STORAGE",
  });
  await post(categories + "/" + categoryId, {
    storeId,
    expectedVersion: 0,
    name: "收纳导航",
    status: "ACTIVE",
    clearPlatformCategory: true,
    reason: "取消类目关联",
  });

  await page.goto(`/#skus?store=${storeId}`);
  await page.getByLabel("访问凭据", { exact: true }).fill(adminToken!);
  await page.getByRole("button", { name: "进入平台", exact: true }).click();
  await page.getByRole("tab", { name: "类目与模板", exact: true }).click();
  await page
    .getByRole("row")
    .filter({ hasText: categoryId })
    .getByRole("button", { name: "绑定记录", exact: true })
    .click();
  const log = page.getByRole("dialog", { name: "类目绑定记录", exact: true });
  await expect(
    log.getByRole("cell", { name: "关联平台类目", exact: true }),
  ).toBeVisible();
  await log
    .getByRole("row")
    .filter({ hasText: "解除平台关联" })
    .getByRole("button")
    .first()
    .click();
  await expect(
    log.getByRole("cell", { name: "HOME_STORAGE", exact: true }),
  ).toBeVisible();
  if (evidence)
    await page.screenshot({ path: evidence + "/oa03-catalog-audit.png" });
});
