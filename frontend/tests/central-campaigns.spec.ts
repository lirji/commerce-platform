import { test, expect, type Page } from "@playwright/test";
import { mkdirSync } from "node:fs";

// 公开DTO夹具验证界面与意图恢复；真正PKCE/授权/预算效果由跨进程演练独立证明。
const tenant = "11111111-1111-4111-8111-111111111111";
const folder = `${process.env.COMMERCE_EVIDENCE_DIR ?? "../.local/central-audiences"}/campaigns-ui`;
const actualView = {
  content: {
    campaignId: "CAM:7",
    version: 7,
    storeId: "S1",
    name: "活动契约验证",
    validFrom: "2026-10-01T00:00:00Z",
    validTo: "2027-01-01T00:00:00Z",
    minimumSpend: "20.00",
    discountAmount: "1.00",
    rule: {
      kind: "COMPARE",
      field: "memberLevel",
      operator: "EQ",
      valueType: "TEXT",
      value: "BASIC",
    },
  },
  merchantId: "MERCHANT",
  status: "DRAFT",
  lockVersion: 0,
};
async function loginFixture(page: Page) {
  await page.addInitScript(() =>
    sessionStorage.setItem(
      "oidc.user:http://127.0.0.1:18090:commerce-ui-contract",
      JSON.stringify({
        access_token: "fixture-central",
        token_type: "Bearer",
        scope: "openid profile",
        profile: { sub: "employee" },
        expires_at: Math.floor(Date.now() / 1000) + 1800,
      }),
    ),
  );
}
async function screenshot(page: Page, name: string) {
  mkdirSync(folder, { recursive: true });
  await page.evaluate(async () => {
    await document.fonts.ready;
    await new Promise((resolve) =>
      requestAnimationFrame(() => requestAnimationFrame(resolve)),
    );
  });
  await page.screenshot({
    path: `${folder}/${name}.png`,
    fullPage: false,
    animations: "disabled",
  });
}
async function fillDraft(page: Page) {
  const dialog = page.getByRole("dialog", {
    name: "创建活动草稿",
    exact: true,
  });
  await dialog.getByLabel("活动编号", { exact: true }).fill("CAM:7");
  await dialog.getByLabel("内容版本（不可变）", { exact: true }).fill("7");
  await dialog.getByLabel("实际门店编号", { exact: true }).fill("S1");
  await dialog.getByLabel("活动名称", { exact: true }).fill("活动契约验证");
  await dialog.getByLabel("开始时间", { exact: true }).fill("2026-10-01T00:00");
  await dialog.getByLabel("结束时间", { exact: true }).fill("2027-01-01T00:00");
  await dialog
    .getByLabel("优惠金额 / 比例折扣上限（元）", { exact: true })
    .fill("1.00");
  await dialog
    .getByRole("textbox", { name: "比较值", exact: true })
    .fill("BASIC");
  return dialog;
}
test.beforeEach(async ({ page }) => {
  test.skip(!process.env.COMMERCE_CENTRAL_UI_URL, "需显式独立SSO预览");
  await loginFixture(page);
});

test("活动创建独立于读取，长表单与关闭保护，丢响应后保留原意图", async ({
  page,
}) => {
  const errors: string[] = [],
    requests: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("request", (request) => {
    if (request.url().includes("/v1/"))
      requests.push(new URL(request.url()).pathname);
  });
  const attempts: { key?: string; body: string | null }[] = [];
  await page.route("**/v1/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/v1/operations/campaigns/create-access")
      return route.fulfill({ json: { allowed: true } });
    if (route.request().method() === "POST" && path === "/v1/admin/campaigns") {
      attempts.push({
        key: route.request().headers()["idempotency-key"],
        body: route.request().postData(),
      });
      if (attempts.length === 1) return route.abort("failed");
      return route.fulfill({ json: actualView });
    }
    return route.fulfill({ status: 403, json: {} });
  });
  await page.goto(
    `${process.env.COMMERCE_CENTRAL_UI_URL}/operations/campaigns?tenant_id=${tenant}`,
  );
  await page.getByRole("tab", { name: "创建草稿", exact: true }).click();
  await page.getByRole("button", { name: "创建活动草稿", exact: true }).click();
  let dialog = await fillDraft(page);
  await screenshot(page, "create-1440");
  for (const width of [390, 320]) {
    await page.setViewportSize({ width, height: 844 });
    await expect
      .poll(() =>
        page.evaluate(() =>
          Math.max(
            document.body.scrollWidth,
            document.documentElement.scrollWidth,
          ),
        ),
      )
      .toBe(width);
    await screenshot(page, `create-${width}`);
    await expect(
      dialog.getByRole("button", { name: "保存活动草稿", exact: true }),
    ).toBeVisible();
  }
  await page.setViewportSize({ width: 1440, height: 1000 });
  await dialog.getByRole("button", { name: "取消", exact: true }).click();
  const confirm = page.getByRole("dialog", { name: "放弃未保存的活动草稿？" });
  await expect(confirm).toBeVisible();
  await screenshot(page, "dirty-confirm");
  await confirm.getByRole("button", { name: "继续编辑", exact: true }).click();
  await expect(dialog.getByLabel("活动编号", { exact: true })).toHaveValue(
    "CAM:7",
  );
  await dialog
    .getByRole("button", { name: "保存活动草稿", exact: true })
    .click();
  await expect(
    dialog.getByText("操作结果尚未确认，请保留原意图重试", { exact: true }),
  ).toBeVisible();
  await expect(
    dialog.getByText(
      "网络响应未知。若刚提交过操作，请保留输入重试或刷新核对。",
      {
        exact: true,
      },
    ),
  ).toHaveCount(1);
  await expect(
    dialog.getByText("操作资格尚未确认", { exact: true }),
  ).toHaveCount(0);
  await expect(
    dialog.getByRole("button", { name: "重新核验创建权限", exact: true }),
  ).toHaveCount(1);
  await screenshot(page, "feedback-unknown-1440");
  await page.setViewportSize({ width: 390, height: 844 });
  await screenshot(page, "feedback-unknown-390");
  await page.setViewportSize({ width: 1440, height: 1000 });
  await dialog
    .getByRole("button", { name: "返回页签（保留原意图）", exact: true })
    .click();
  await page
    .getByRole("dialog", { name: "保留原意图并返回页签？", exact: true })
    .getByRole("button", { name: "保留并返回", exact: true })
    .click();
  await page.getByRole("tab", { name: "活动目录", exact: true }).click();
  await page.getByRole("tab", { name: "创建草稿", exact: true }).click();
  await page.getByRole("button", { name: "退出登录", exact: true }).click();
  await page
    .getByRole("dialog", { name: "离开活动工作区？", exact: true })
    .getByRole("button", { name: "留在当前页", exact: true })
    .click();
  const createPanel = page.getByRole("tabpanel", {
    name: "创建草稿",
    exact: true,
  });
  await createPanel
    .getByRole("button", { name: "重新核验创建草稿权限", exact: true })
    .click();
  await createPanel
    .getByRole("button", { name: "恢复原创建意图", exact: true })
    .click();
  await screenshot(page, "unknown");
  await dialog
    .getByRole("button", { name: "重新核验创建权限", exact: true })
    .first()
    .click();
  await expect(
    dialog.getByRole("button", { name: "原样重试创建", exact: true }),
  ).toBeVisible();
  await expect(dialog.getByLabel("活动编号", { exact: true })).toBeDisabled();
  await dialog
    .getByRole("button", { name: "原样重试创建", exact: true })
    .click();
  await expect(page.getByText("操作结果已确认", { exact: true })).toBeVisible();
  expect(attempts).toHaveLength(2);
  expect(attempts[0].key).toBeTruthy();
  expect(attempts[0]).toEqual(attempts[1]);
  await screenshot(page, "create-success");
  expect(errors).toEqual([]);
  expect(
    requests.every((path) =>
      /^\/v1\/(admin\/campaigns|operations\/campaigns\/(create|preview)-access)$/.test(
        path,
      ),
    ),
  ).toBe(true);
});

test("预算独立入口只读取预算，401卸载敏感视图", async ({ page }) => {
  const paths: string[] = [];
  let invalid = false;
  await page.route("**/v1/**", (route) => {
    const path = new URL(route.request().url()).pathname;
    paths.push(path);
    return invalid
      ? route.fulfill({ status: 401, json: {} })
      : route.fulfill({
          json: [
            {
              budgetId: "B1",
              campaignId: "CAM:7",
              version: 7,
              cap: "20.00",
              held: "1.00",
              spent: "0.00",
            },
          ],
        });
  });
  await page.goto(
    `${process.env.COMMERCE_CENTRAL_UI_URL}/operations/campaign-budgets?tenant_id=${tenant}`,
  );
  await expect(
    page.getByRole("cell", { name: "CAM:7", exact: true }),
  ).toBeVisible();
  await screenshot(page, "budget-1440");
  await page.setViewportSize({ width: 390, height: 844 });
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth))
    .toBe(390);
  await screenshot(page, "budget-390");
  invalid = true;
  await page.getByRole("button", { name: "刷新预算余额", exact: true }).click();
  await expect(
    page.getByText("登录已失效，请重新登录", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("cell", { name: "CAM:7", exact: true }),
  ).toHaveCount(0);
  expect(paths.every((path) => path === "/v1/admin/campaign-budgets")).toBe(
    true,
  );
});

test("独立审批操作使用内容版本和服务端锁版本，预览展示真实DTO结构", async ({
  page,
}) => {
  const writes: { path: string; body: unknown }[] = [];
  await page.route("**/v1/**", (route) => {
    const request = route.request(),
      path = new URL(request.url()).pathname;
    if (path.endsWith("-access"))
      return route.fulfill({ json: { allowed: true } });
    if (request.method() === "POST") {
      writes.push({ path, body: request.postDataJSON() });
      if (path.endsWith("/preview"))
        return route.fulfill({
          json: {
            gross: "25.00",
            discount: "1.00",
            payable: "24.00",
            lines: [
              {
                skuId: "SKU",
                gross: "25.00",
                discount: "1.00",
                payable: "24.00",
              },
            ],
            trace: [{ campaignId: "CAM:7", version: 7, reason: "ELIGIBLE" }],
            sources: [],
            notice: "current facts",
            selected: { campaignId: "CAM:7", version: 7 },
          },
        });
      return route.fulfill({
        json: { ...actualView, status: "IN_REVIEW", lockVersion: 9 },
      });
    }
    return route.fulfill({ status: 403, json: {} });
  });
  await page.goto(
    `${process.env.COMMERCE_CENTRAL_UI_URL}/operations/campaigns?tenant_id=${tenant}`,
  );
  await page.getByRole("tab", { name: "版本操作", exact: true }).click();
  const panel = page.getByRole("tabpanel", { name: "版本操作", exact: true });
  await panel.getByLabel("实际活动编号", { exact: true }).fill("CAM:7");
  await panel.getByLabel("内容版本（不可变）", { exact: true }).fill("7");
  await panel.getByRole("button", { name: "只读预览", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("实际会员编号", { exact: true }).fill("M1");
  await dialog.getByLabel("实际SKU编号", { exact: true }).fill("SKU");
  await dialog.getByLabel("数量", { exact: true }).fill("1");
  await dialog
    .getByRole("button", { name: "运行只读预览", exact: true })
    .click();
  await expect(
    dialog.getByText("24.00", { exact: true }).first(),
  ).toBeVisible();
  await screenshot(page, "preview-1440");
  await page.setViewportSize({ width: 390, height: 844 });
  await screenshot(page, "preview-390");
  await dialog
    .locator(".ant-modal-footer")
    .getByRole("button", { name: "关闭", exact: true })
    .click();
  await panel.getByLabel("本次操作", { exact: true }).click();
  await panel.getByLabel("本次操作", { exact: true }).press("ArrowDown");
  await panel.getByLabel("本次操作", { exact: true }).press("Enter");
  await panel
    .getByLabel("状态锁版本（expectedVersion）", { exact: true })
    .fill("0");
  await panel.getByRole("button", { name: "提交审批", exact: true }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await screenshot(page, "submit-confirm-390");
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "确认提交审批", exact: true })
    .click();
  await expect(
    panel.getByText("操作结果已确认", { exact: true }),
  ).toBeVisible();
  await expect(
    panel.getByLabel("状态锁版本（expectedVersion）", { exact: true }),
  ).toHaveValue("9");
  expect(writes[1]).toEqual({
    path: "/v1/admin/campaigns/CAM%3A7/7/submit",
    body: { expectedVersion: 0 },
  });
});
