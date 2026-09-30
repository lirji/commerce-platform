import { expect, test, type Page } from "@playwright/test";
const evidence =
  process.env.COMMERCE_EVIDENCE_DIR ?? "../.local/b-console-craft";

// DashboardController 的公开汇总形状；负净收来自截至投影时已知的成功退款。
async function openDashboard(page: Page, negativeOnly = false) {
  await page.route("**/v1/**", async (route) => {
    const path = new URL(route.request().url()).pathname.slice(3);
    let body: unknown = [];
    if (path === "/me")
      body = { tenantId: "craft-tenant", actorId: "admin", role: "ADMIN" };
    else if (path === "/stores")
      body = [
        {
          storeId: "craft-store",
          merchantId: "craft-merchant",
          name: "经营验收门店",
          status: "ACTIVE",
          version: 1,
        },
      ];
    else if (path === "/runtime-capabilities")
      body = { sandboxEnabled: false, workersEnabled: false };
    else if (path === "/admin/dashboard")
      body = {
        storeId: "craft-store",
        from: "2026-09-01",
        to: "2026-09-30",
        generatedAt: "2026-09-30T12:00:00Z",
        members: { total: 7, active: 6, frozen: 1, closed: 0 },
        catalog: { total: 3, active: 2, frozen: 1 },
        daily: Array.from({ length: 30 }, (_, index) => ({
          day: `2026-09-${String(index + 1).padStart(2, "0")}`,
          orders: index === 0 ? 1 : 0,
          paidOrders: index === 0 ? 1 : 0,
          received: index === 0 && !negativeOnly ? "100.00" : "0.00",
          refunded: index === 1 ? "35.00" : "0.00",
          netReceipts:
            index === 0 && !negativeOnly
              ? "100.00"
              : index === 1
                ? "-35.00"
                : "0.00",
          discountGranted: "0.00",
          platformFunding: "0.00",
          merchantFunding: "0.00",
        })),
        totals: {
          paidOrders: 1,
          received: negativeOnly ? "0.00" : "100.00",
          refunded: "35.00",
          netReceipts: negativeOnly ? "-35.00" : "65.00",
          discountGranted: "0.00",
        },
        coverage: "成交净收不等于利润",
      };
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(body),
    });
  });
  await page.goto("/#dashboard?store=craft-store");
  await page
    .getByLabel("访问凭据", { exact: true })
    .fill("craft-test-boundary");
  await page.getByRole("button", { name: "进入平台", exact: true }).click();
  await expect(page.locator(".trend-column")).toHaveCount(30);
}

test("每日趋势保留正、负、零金额，可键盘探索和刷新恢复指标", async ({
  page,
}) => {
  await openDashboard(page);
  const positive = page.locator(".trend-bar[data-value='100.00']");
  const negative = page.locator(".trend-bar[data-value='-35.00']");
  const positiveBox = (await positive.boundingBox())!;
  const negativeBox = (await negative.boundingBox())!;
  expect(positiveBox.height).toBeGreaterThan(0);
  expect(negativeBox.height).toBeGreaterThan(0);
  expect(positiveBox.y + positiveBox.height).toBeCloseTo(negativeBox.y, 0);
  expect(
    await page
      .locator(".trend-bar[data-value='0.00']")
      .first()
      .evaluate((element) => element.getBoundingClientRect().height),
  ).toBe(0);
  await page.locator(".trend-column").last().focus();
  await page.keyboard.press("Home");
  await expect(page.locator(".trend-readout")).toContainText("¥100.00");
  await page.keyboard.press("ArrowRight");
  await expect(page.locator(".trend-readout")).toContainText("¥-35.00");
  await expect(page.locator(".trend-column").nth(1)).toBeFocused();
  await page.screenshot({
    path: `${evidence}/signed-trend-desktop.png`,
    fullPage: true,
  });
  await page
    .locator(".ant-segmented-item")
    .filter({ hasText: /^退款$/ })
    .click();
  await page.getByRole("button", { name: "查看每日数据", exact: true }).click();
  await page.reload();
  await expect(
    page.getByRole("radio", { name: "退款", exact: true }),
  ).toBeChecked();
  await expect(
    page.getByRole("button", { name: "收起每日数据", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "退出", exact: true }),
  ).toBeVisible();
  await page.keyboard.press("Control+k");
  await expect(
    page.getByRole("combobox", { name: "搜索功能", exact: true }),
  ).toBeFocused();
  await page.keyboard.type("商品管理");
  await page.getByText("商品管理", { exact: true }).last().click();
  await expect(
    page.getByRole("heading", { name: "商品经营", exact: true }),
  ).toBeVisible();
  await page.getByRole("tab", { name: "商品资料（SPU）", exact: true }).click();
  await page.getByRole("button", { name: "创建商品", exact: true }).click();
  const identifier = page.getByRole("textbox", {
    name: "商品标识",
    exact: true,
  });
  await identifier.focus();
  await expect(identifier).toHaveAttribute("aria-required", "true");
  await page.keyboard.press("Control+k");
  await expect(identifier).toBeFocused();
  await page.getByRole("button", { name: "关闭", exact: true }).click();
});

test("全部净收为负仍有真实坐标，窄屏可拖动日期与返回零值", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openDashboard(page, true);
  const negativeBox = (await page
    .locator(".trend-bar[data-value='-35.00']")
    .boundingBox())!;
  const plotBox = (await page.locator(".trend-plot").boundingBox())!;
  expect(negativeBox.height).toBeCloseTo(plotBox.height, 0);
  await page
    .getByRole("slider", { name: "选择趋势日期", exact: true })
    .fill("1");
  await expect(page.locator(".trend-readout")).toContainText("¥-35.00");
  await page.getByRole("button", { name: "后一天", exact: true }).click();
  await expect(page.locator(".trend-readout")).toContainText("¥0.00");
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth + 1,
    ),
  ).toBe(true);
  await page.screenshot({
    path: `${evidence}/signed-trend-mobile.png`,
    fullPage: true,
  });
});

test("旅程关系图准确表达分支与汇合，节点可键盘核对且返回焦点", async ({
  page,
}) => {
  await openDashboard(page);
  const writes: string[] = [];
  await page.route("**/v1/admin/journeys?*", (route) => {
    if (route.request().method() !== "GET")
      writes.push(route.request().method());
    return route.fulfill({
      json: [
        {
          status: "DRAFT",
          lockVersion: 1,
          content: {
            journeyId: "journey-graph",
            name: "会员分层关怀",
            version: 3,
            storeId: "craft-store",
            trigger: "MANUAL",
            validFrom: "2026-09-01T00:00:00Z",
            validTo: "2026-10-01T00:00:00Z",
            maxDurationSeconds: 86400,
            entry: "check",
            nodes: [
              {
                id: "check",
                kind: "DECIDE",
                rule: {
                  kind: "COMPARE",
                  field: "memberLevel",
                  operator: "EQ",
                  valueType: "TEXT",
                  value: "GOLD",
                },
                yesNext: "coupon",
                noNext: "wait",
              },
              {
                id: "coupon",
                kind: "COUPON",
                coupon: { definitionId: "member-coupon", version: 2 },
                next: "notify",
              },
              { id: "wait", kind: "WAIT", seconds: 3600, next: "notify" },
              {
                id: "notify",
                kind: "NOTIFY",
                title: "经营关怀",
                body: "当前版本的实际通知内容",
                next: "end",
              },
              { id: "end", kind: "END" },
            ],
          },
        },
      ],
    });
  });
  await page
    .getByRole("combobox", { name: "搜索功能", exact: true })
    .fill("营销旅程");
  await page
    .locator(".ant-select-item-option-content")
    .filter({ hasText: /^营销旅程$/ })
    .click();
  const open = page.getByRole("button", { name: "查看节点", exact: true });
  await open.click();
  const map = page.getByRole("region", { name: "旅程版本关系图", exact: true });
  await expect(map.locator(".journey-map-node")).toHaveCount(5);
  await expect(map.locator(".journey-edge")).toHaveCount(5);
  await expect(
    map.locator("[data-from='check'][data-to='coupon']"),
  ).toContainText("命中");
  await expect(
    map.locator("[data-from='check'][data-to='wait']"),
  ).toContainText("未命中");
  await expect(
    map.getByRole("button", { name: /^规则分支 check（入口）/ }),
  ).toHaveAttribute("aria-pressed", "true");
  await map.getByRole("button", { name: /^等待 wait/ }).click();
  await expect(map.locator(".journey-node-detail")).toContainText("3600");
  const notify = map.getByRole("button", { name: /^站内触达 notify/ });
  await notify.focus();
  await page.keyboard.press("Enter");
  await expect(notify).toHaveAttribute("aria-pressed", "true");
  await expect(map.locator(".journey-node-detail")).toContainText(
    "当前版本的实际通知内容",
  );
  await expect(
    map
      .locator(".journey-node-detail")
      .getByText("当前版本的实际通知内容", { exact: true }),
  ).toBeInViewport();
  await page.getByRole("button", { name: "收起视图", exact: true }).click();
  await expect(map.locator(".journey-map-content")).toHaveCSS(
    "display",
    "block",
  );
  await page.getByRole("button", { name: "展开视图", exact: true }).click();
  await expect(map.locator(".journey-map-content")).toHaveCSS(
    "display",
    "grid",
  );
  await page.screenshot({
    path: `${evidence}/journey-graph-1440.png`,
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect
    .poll(() =>
      page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth + 1,
      ),
    )
    .toBe(true);
  await map.getByRole("button", { name: /^结束 end/ }).focus();
  await page.keyboard.press("Enter");
  await expect(map.locator(".journey-node-detail")).toContainText("end");
  await page.screenshot({
    path: `${evidence}/journey-graph-390.png`,
    fullPage: true,
  });
  await page.getByRole("button", { name: "返回列表", exact: true }).click();
  await expect(open).toBeFocused();
  expect(writes).toEqual([]);
});
