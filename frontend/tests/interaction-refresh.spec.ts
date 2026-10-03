import { expect, test, type Page } from "@playwright/test";
import type { Order } from "../src/shared/contracts";
import { assertButtonSizes, assertCentered } from "./presentation";

// 仅在测试边界返回正式 DTO；产品页面始终读取真实接口。
async function openCampaign(page: Page) {
  await page.route("**/v1/**", async (route) => {
    const pathname = new URL(route.request().url()).pathname;
    let body: unknown = [];
    if (pathname === "/v1/me")
      body = { tenantId: "ui-test", actorId: "admin", role: "ADMIN" };
    if (pathname === "/v1/stores")
      body = [
        {
          storeId: "store-ui",
          merchantId: "merchant-ui",
          name: "测试店铺",
          status: "ACTIVE",
          version: 1,
        },
      ];
    if (pathname === "/v1/runtime-capabilities")
      body = { sandboxEnabled: false, workersEnabled: false };
    if (pathname === "/v1/admin/dashboard") body = null;
    if (pathname === "/v1/admin/campaigns")
      body = [
        {
          content: {
            campaignId: "campaign-ui",
            storeId: "store-ui",
            version: 20260923,
            name: "会员满100减25",
            minimumSpend: "100.00",
            discountAmount: "25.00",
            validFrom: "2026-01-01T00:00:00Z",
            validTo: "2029-01-01T00:00:00Z",
            rule: {
              kind: "COMPARE",
              field: "memberLevel",
              operator: "EQ",
              valueType: "TEXT",
              value: "VIP",
            },
          },
          status: "PUBLISHED",
          lockVersion: 1,
        },
      ];
    await route.fulfill({ json: body });
  });
  await page.goto("/");
  await expect(
    page.getByRole("button", { name: "进入平台", exact: true }),
  ).toHaveCSS("height", "38px");
  await page.getByLabel("访问凭据", { exact: true }).fill("ui-test-credential");
  await page.getByRole("button", { name: "进入平台", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "退出", exact: true }),
  ).toBeVisible();
  await expect(page).toHaveURL(/#dashboard\?store=store-ui$/);
  await page.evaluate(() => {
    location.hash = "campaigns?store=store-ui";
  });
  await expect(
    page.getByRole("button", { name: "查看配置", exact: true }),
  ).toBeVisible();
}

test("行操作尺寸一致，单页明确禁用翻页，配置使用业务语言", async ({ page }) => {
  await openCampaign(page);
  const heights = await page
    .locator(".row-actions button")
    .evaluateAll((buttons) =>
      buttons.map((button) =>
        Math.round(button.getBoundingClientRect().height),
      ),
    );
  expect(heights.length).toBe(4);
  expect(new Set(heights)).toEqual(new Set([38]));
  await assertButtonSizes(page);
  await expect(
    page.getByRole("button", { name: "下一页", exact: true }),
  ).toBeDisabled();
  await expect(
    page.getByRole("button", { name: "上一页", exact: true }),
  ).toBeDisabled();
  await page.getByRole("button", { name: "查看配置", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await assertCentered(page);
  await assertButtonSizes(page);
  const compact = (await dialog.boundingBox())!;
  expect(compact.width).toBeLessThanOrEqual(600);
  expect(compact.height).toBeLessThanOrEqual(680);
  await expect(dialog.locator(".rule-summary")).toHaveText("会员等级等于VIP");
  await dialog.getByRole("button", { name: "展开视图", exact: true }).click();
  await expect(
    dialog.getByRole("button", { name: "收起视图", exact: true }),
  ).toBeVisible();
  await dialog.getByRole("button", { name: "返回列表", exact: true }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
});

test("复制版本支持未保存提醒、继续编辑与丢弃，窄屏固定保存入口", async ({
  page,
}) => {
  await openCampaign(page);
  await page.getByRole("button", { name: "复制新版本", exact: true }).click();
  await expect(page.getByLabel("版本", { exact: true })).toHaveValue(
    "20260924",
  );
  await page.getByLabel("活动名称", { exact: true }).fill("尚未保存的名称");
  await page.getByRole("button", { name: "取消", exact: true }).click();
  await page.getByRole("button", { name: "继续编辑", exact: true }).click();
  await expect(page.getByLabel("活动名称", { exact: true })).toHaveValue(
    "尚未保存的名称",
  );
  await page.setViewportSize({ width: 390, height: 844 });
  await assertCentered(page);
  await assertButtonSizes(page);
  const save = await page
    .getByRole("button", { name: "保存活动草稿", exact: true })
    .boundingBox();
  expect(save).not.toBeNull();
  expect(save!.y + save!.height).toBeLessThanOrEqual(844);
  await page.getByRole("button", { name: "取消", exact: true }).click();
  await page.getByRole("button", { name: "放弃修改", exact: true }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.getByRole("button", { name: "复制新版本", exact: true }).click();
  await expect(page.getByLabel("活动名称", { exact: true })).toHaveValue(
    "会员满100减25",
  );
});

test("预览的帮助与校验同时可见，非法清单不会发送请求", async ({ page }) => {
  await openCampaign(page);
  let writes = 0;
  page.on("request", (request) => {
    if (request.method() === "POST" && request.url().includes("/preview"))
      writes++;
  });
  await page.getByRole("button", { name: "预览优惠", exact: true }).click();
  await page.getByRole("button", { name: "计算预览", exact: true }).click();
  await expect(page.getByText("请输入购物清单", { exact: true })).toBeVisible();
  await expect(
    page.getByText("每行填写 SKU标识,数量。价格与会员事实由服务端读取。", {
      exact: true,
    }),
  ).toBeVisible();
  await page.getByLabel("会员标识", { exact: true }).fill("member-ui");
  await page.getByLabel("购物清单", { exact: true }).fill("sku-ui,0");
  await page.getByRole("button", { name: "计算预览", exact: true }).click();
  await expect(
    page.getByText("每行填写 SKU标识,正整数数量", { exact: true }),
  ).toBeVisible();
  expect(writes).toBe(0);
});

function orderFixture(n: number): Order {
  return {
    orderId: `order-${n}`,
    memberId: "member-ui",
    storeId: "store-ui",
    payable: "59.00",
    status: "PAID",
    paymentKind: "CHANNEL_REQUIRED",
    version: 1,
    createdAt: "2026-01-01T00:00:00Z",
    expiresAt: "2026-01-01T00:15:00Z",
    items: [
      {
        skuId: "sku-ui",
        title: "精品咖啡",
        quantity: 1,
        unitPrice: "59.00",
        gross: "59.00",
        discount: "0.00",
        payable: "59.00",
      },
    ],
  };
}

test("订单完整详情保留翻页位置、焦点与深链接，浏览器后退回到列表", async ({
  page,
}) => {
  await openCampaign(page);
  await page.route("**/v1/admin/orders**", async (route) => {
    const url = new URL(route.request().url());
    const body =
      url.pathname === "/v1/admin/orders"
        ? url.searchParams.get("after")
          ? [orderFixture(51)]
          : Array.from({ length: 50 }, (_, i) => orderFixture(i + 1))
        : orderFixture(Number(url.pathname.split("order-")[1]));
    await route.fulfill({ json: body });
  });
  await page.evaluate(() => {
    location.hash = "orders?store=store-ui";
  });
  await page.getByRole("button", { name: "下一页", exact: true }).click();
  const row = page.getByRole("row").filter({ hasText: "order-51" });
  await expect(row).toBeVisible();
  await row.getByRole("button", { name: "完整详情", exact: true }).click();
  await expect(page).toHaveURL(/order=order-51/);
  await expect(
    page.getByRole("heading", { name: "订单完整详情", exact: true }),
  ).toBeVisible();
  await expect(page.locator(".order-workspace")).toContainText("精品咖啡");
  await page.getByRole("button", { name: "返回订单列表", exact: true }).click();
  await expect(row).toBeVisible();
  await expect(
    row.getByRole("button", { name: "完整详情", exact: true }),
  ).toBeFocused();
  await row.getByRole("button", { name: "完整详情", exact: true }).click();
  await expect(page).toHaveURL(/order=order-51/);
  await page.goBack();
  await expect(row).toBeVisible();
  await row.getByRole("button", { name: "完整详情", exact: true }).click();
  await page.reload();
  await expect(page.getByLabel("访问凭据", { exact: true })).toHaveCount(0);
  await expect(
    page.getByRole("heading", { name: "订单完整详情", exact: true }),
  ).toBeVisible();
  await expect(page.locator(".order-workspace")).toContainText("order-51");
});

test("完整订单详情显式显示加载与无权错误，可重试并返回列表", async ({
  page,
}) => {
  await openCampaign(page);
  let release = () => {};
  const ready = new Promise<void>((resolve) => {
    release = resolve;
  });
  let deny = true;
  await page.route("**/v1/admin/orders**", async (route) => {
    if (new URL(route.request().url()).pathname === "/v1/admin/orders")
      return route.fulfill({ json: [orderFixture(1)] });
    await ready;
    await route.fulfill(
      deny
        ? { status: 403, json: { message: "无权查看这笔订单" } }
        : { json: orderFixture(1) },
    );
  });
  await page.evaluate(() => {
    location.hash = "orders?store=store-ui";
  });
  await page.getByRole("button", { name: "完整详情", exact: true }).click();
  await expect(
    page.getByText("正在读取订单详情", { exact: true }),
  ).toBeVisible();
  release();
  await expect(
    page.getByText("无权查看这笔订单", { exact: true }),
  ).toBeVisible();
  deny = false;
  await page.getByRole("button", { name: "重新加载详情", exact: true }).click();
  await expect(page.locator(".order-workspace")).toContainText("精品咖啡");
  await page.getByRole("button", { name: "返回订单列表", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "查看详情", exact: true }),
  ).toBeVisible();
});

test("320px 导航、详情和编辑弹层居中且按钮共用尺寸，关闭后返回触发入口", async ({
  page,
}) => {
  await openCampaign(page);
  await page.setViewportSize({ width: 320, height: 844 });
  const menu = page.getByRole("button", { name: "打开经营导航", exact: true });
  await menu.click();
  await assertCentered(page);
  await assertButtonSizes(page);
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "关闭", exact: true })
    .click();
  await expect(menu).toBeFocused();
  const opener = page.getByRole("button", { name: "查看配置", exact: true });
  await opener.click();
  await assertCentered(page);
  await assertButtonSizes(page);
  await page.screenshot({
    path: `${process.env.COMMERCE_EVIDENCE_DIR ?? "../.local/frontend-modal-buttons"}/campaign-detail-320.png`,
  });
  await page.getByRole("button", { name: "返回列表", exact: true }).click();
  await expect(opener).toBeFocused();
  await page.getByRole("button", { name: "复制新版本", exact: true }).click();
  await assertCentered(page);
  await assertButtonSizes(page);
  await expect(
    page.getByRole("button", { name: "保存活动草稿", exact: true }),
  ).toBeInViewport();
  await page.screenshot({
    path: `${process.env.COMMERCE_EVIDENCE_DIR ?? "../.local/frontend-modal-buttons"}/campaign-edit-320.png`,
  });
  const body = page.getByRole("dialog").locator(".ant-modal-body");
  expect(
    await body.evaluate(
      (element) => element.scrollHeight > element.clientHeight,
    ),
  ).toBe(true);
  await page.getByRole("button", { name: "取消", exact: true }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
});

test("会员商城、分类、商品详情与购物袋共用按钮尺寸，三种屏宽弹层居中", async ({
  page,
}) => {
  // 仅测试展示边界；产品仍从接口取正式 CatalogItem，未模拟报价或下单成功。
  const sku = {
    skuId: "sku-display",
    storeId: "store-display",
    title: "测试咖啡",
    unitPrice: "59.00",
    revision: 1,
    status: "ACTIVE",
    categoryId: "category-display",
    categoryName: "咖啡",
    description: "展示回归",
    images: [],
    specifications: [],
  };
  await page.route("**/v1/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    let json: unknown = [];
    if (path === "/v1/me")
      json = { tenantId: "ui-test", actorId: "member", role: "MEMBER" };
    else if (path === "/v1/stores")
      json = [
        {
          storeId: "store-display",
          merchantId: "merchant-display",
          name: "展示店铺",
          status: "ACTIVE",
          version: 1,
        },
      ];
    else if (path === "/v1/runtime-capabilities")
      json = { sandboxEnabled: false, workersEnabled: false };
    else if (path === "/v1/catalog/search") json = [sku];
    else if (path === "/v1/catalog/items/sku-display") json = sku;
    else if (path === "/v1/catalog/categories")
      json = [
        {
          categoryId: "category-display",
          storeId: "store-display",
          name: "咖啡",
          depth: 1,
          status: "ACTIVE",
          version: 1,
        },
      ];
    else if (path === "/v1/members/me/points") json = { available: 0 };
    await route.fulfill({ json });
  });
  await page.goto("/");
  await page
    .getByLabel("访问凭据", { exact: true })
    .fill("member-display-fixture");
  await page.getByRole("button", { name: "进入平台", exact: true }).click();
  await expect(page.locator(".shop-channel")).toHaveCount(2);
  for (const width of [1440, 390, 320]) {
    await page.setViewportSize({ width, height: width === 1440 ? 1000 : 844 });
    await assertButtonSizes(page);
    // 会员横向导航通过 ResizeObserver 重排；等待真实布局收敛，持续溢出仍会失败。
    await expect
      .poll(
        () =>
          page.evaluate(
            () => document.documentElement.scrollWidth <= innerWidth + 1,
          ),
        { message: `${width}px 商城布局稳定后不应横向溢出` },
      )
      .toBe(true);
    await page.getByRole("button", { name: "测试咖啡", exact: true }).click();
    await assertCentered(page);
    await assertButtonSizes(page);
    const product = (await page.getByRole("dialog").boundingBox())!;
    expect(product.width).toBeLessThanOrEqual(720);
    await page.screenshot({
      path: `${process.env.COMMERCE_EVIDENCE_DIR ?? "../.local/frontend-modal-buttons"}/member-product-${width}.png`,
    });
    await page.getByRole("button", { name: "返回店铺", exact: true }).click();
  }
  await page.getByRole("button", { name: "加入购物袋", exact: true }).click();
  for (const width of [1440, 390, 320]) {
    await page.setViewportSize({ width, height: width === 1440 ? 1000 : 844 });
    await page.getByRole("button", { name: "购物袋 · 1", exact: true }).click();
    await assertCentered(page);
    await assertButtonSizes(page);
    const checkout = (await page.getByRole("dialog").boundingBox())!;
    expect(checkout.width).toBeLessThanOrEqual(520);
    expect(checkout.height).toBeLessThanOrEqual(
      Math.min(680, page.viewportSize()!.height * 0.82),
    );
    await page.screenshot({
      path: `${process.env.COMMERCE_EVIDENCE_DIR ?? "../.local/frontend-modal-buttons"}/member-checkout-${width}.png`,
    });
    await page.getByRole("button", { name: "返回列表", exact: true }).click();
  }
});
