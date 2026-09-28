import { expect, test, type Page } from "@playwright/test";

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
  await page.getByLabel("访问凭据", { exact: true }).fill("ui-test-credential");
  await page.getByRole("button", { name: "进入平台", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "退出", exact: true }),
  ).toBeVisible();
  await page.evaluate(() => {
    location.hash = "campaigns?store=store-ui";
  });
  await expect(
    page.getByRole("button", { name: "查看配置", exact: true }),
  ).toBeVisible();
}

test("行操作尺寸一致，单页隐藏无用分页，配置使用业务语言", async ({ page }) => {
  await openCampaign(page);
  const heights = await page
    .locator(".row-actions button")
    .evaluateAll((buttons) =>
      buttons.map((button) =>
        Math.round(button.getBoundingClientRect().height),
      ),
    );
  expect(heights.length).toBe(4);
  expect(new Set(heights).size).toBe(1);
  await expect(
    page.getByRole("button", { name: "下一页", exact: true }),
  ).toHaveCount(0);
  await page.getByRole("button", { name: "查看配置", exact: true }).click();
  const drawer = page.getByRole("dialog");
  await expect(drawer.locator(".rule-summary")).toHaveText("会员等级等于VIP");
  await drawer.getByRole("button", { name: "展开视图", exact: true }).click();
  await expect(
    drawer.getByRole("button", { name: "收起视图", exact: true }),
  ).toBeVisible();
  await drawer.getByRole("button", { name: "返回列表", exact: true }).click();
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
