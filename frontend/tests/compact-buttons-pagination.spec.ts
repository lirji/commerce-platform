import { expect, test, type Page } from "@playwright/test";
import { mkdirSync } from "node:fs";
import { assertButtonSizes } from "./presentation";

// 测试边界使用扫描正式字段；不向数据库写入演示记录或执行旅程命令。
async function openScans(page: Page) {
  let populated = false;
  await page.route("**/v1/**", async (route) => {
    const url = new URL(route.request().url());
    let body: unknown = [];
    if (url.pathname === "/v1/me")
      body = { tenantId: "compact-ui", actorId: "admin", role: "ADMIN" };
    if (url.pathname === "/v1/stores")
      body = [
        {
          storeId: "compact-store",
          merchantId: "compact-merchant",
          name: "操作体验验收门店",
          status: "ACTIVE",
          version: 0,
        },
      ];
    if (url.pathname === "/v1/runtime-capabilities")
      body = { sandboxEnabled: false, workersEnabled: false };
    if (url.pathname.endsWith("rule-fields")) body = {};
    if (url.pathname === "/v1/admin/journey-scans" && populated)
      body = Array.from(
        { length: url.searchParams.get("after") ? 1 : 50 },
        (_, i) => ({
          journeyId: `scan-${String(i + 1).padStart(2, "0")}`,
          journeyVersion: 1,
          status: "SCANNING",
          memberCursor: "",
          createdBefore: "2026-10-01T00:00:00Z",
          nextDue: "2026-10-03T00:00:00Z",
          scanned: 10,
          enrolled: 2,
          attempts: 0,
          version: 0,
        }),
      );
    await route.fulfill({ json: body });
  });
  await page.goto("/#journeys?store=compact-store");
  await page.getByLabel("访问凭据", { exact: true }).fill("compact-fixture");
  await page.getByRole("button", { name: "进入平台", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "刷新扫描", exact: true }),
  ).toBeVisible();
  return () => {
    populated = true;
  };
}

/** 相邻项目必须有实际间距；换行后仍可分辨，避免片段被 Space 合成一个项目。 */
async function assertPagerSpacing(page: Page) {
  const pager = page.locator(".pager-navigation").filter({
    has: page.getByRole("button", { name: "扫描首页", exact: true }),
  });
  const bounds = await pager.locator(":scope > *").evaluateAll((elements) =>
    elements.map((element) => {
      const box = element.getBoundingClientRect();
      return { x: box.x, y: box.y, right: box.right, bottom: box.bottom };
    }),
  );
  expect(bounds).toHaveLength(5);
  for (let i = 1; i < bounds.length; i++) {
    const previous = bounds[i - 1];
    const current = bounds[i];
    expect(
      current.x - previous.right >= 7 || current.y - previous.bottom >= 7,
      `分页第${i}和第${i + 1}项应有间距`,
    ).toBe(true);
  }
  // Ant 响应式导航通过 ResizeObserver 更新，核对稳定后的真实手机布局。
  await expect
    .poll(() =>
      page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth + 1,
      ),
    )
    .toBe(true);
  return pager;
}

test("扫描分页数量、页码和紧凑按钮分开排列，手机换行，前后页保持有效", async ({
  page,
}) => {
  const populate = await openScans(page);
  const folder =
    process.env.COMMERCE_EVIDENCE_DIR ?? "../.local/compact-buttons-pagination";
  mkdirSync(folder, { recursive: true });
  for (const width of [1440, 390, 320]) {
    await page.setViewportSize({ width, height: 1000 });
    const pager = await assertPagerSpacing(page);
    await expect(pager).toContainText("本页 0 条 · 每页最多50条");
    await expect(pager).toContainText("第1页");
    await expect(pager.getByRole("button", { name: "上一页" })).toBeDisabled();
    await assertButtonSizes(page);
    await pager.screenshot({ path: `${folder}/scan-pager-empty-${width}.png` });
  }
  populate();
  await page.getByRole("button", { name: "刷新扫描", exact: true }).click();
  let pager = await assertPagerSpacing(page);
  await expect(pager).toContainText("本页 50 条");
  await pager.getByRole("button", { name: "下一批扫描" }).click();
  await expect(pager).toContainText("第2页");
  await expect(pager).toContainText("本页 1 条");
  await expect(pager.getByRole("button", { name: "上一页" })).toBeEnabled();
  await expect(
    pager.getByRole("button", { name: "下一批扫描" }),
  ).toBeDisabled();
  pager = await assertPagerSpacing(page);
  await pager.screenshot({ path: `${folder}/scan-pager-last-320.png` });
  await pager.getByRole("button", { name: "上一页" }).click();
  await expect(pager).toContainText("第1页");
  await expect(pager).toContainText("本页 50 条");
});
