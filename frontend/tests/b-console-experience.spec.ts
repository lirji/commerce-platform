import { expect, test, type Page } from "@playwright/test";

// 仅测试边界使用服务端公开DTO，真实业务页面不内置任何夹具。
async function fixture(
  page: Page,
  options: { platform?: boolean; unknown?: boolean } = {},
) {
  let identityStatus = 200;
  const writes: { path: string; body: unknown; key: string | undefined }[] = [];
  const reads: string[] = [];
  let recovered = false;
  let status = "RUNNING";
  const job = () => ({
    jobId: "replay-one",
    consumerId: "marketing-effects-v1",
    eventTypes: "order.paid.v1",
    mode: "UNPROCESSED",
    status,
    examined: 2,
    executed: 1,
    alreadyProcessed: 1,
    failed: 0,
    maxEvents: 100,
    reason: "修复投影",
    version: status === "RUNNING" ? 0 : 1,
    fromAt: "2026-01-01T00:00:00Z",
    toAt: "2026-01-02T00:00:00Z",
    createdAt: "2026-01-02T00:01:00Z",
  });
  await page.route("**/v1/**", async (route) => {
    const path = new URL(route.request().url()).pathname.slice(3);
    const method = route.request().method();
    let body: unknown = [];
    if (method === "GET") reads.push(path);
    if (path === "/me") {
      if (identityStatus !== 200 || options.platform)
        return route.fulfill({
          status: options.platform ? 403 : identityStatus,
          json: { message: "身份暂不可用" },
        });
      body = { tenantId: "ui-test", actorId: "admin", role: "ADMIN" };
    } else if (path === "/platform/me")
      body = {
        tenantId: "platform",
        actorId: "sre",
        role: "PLATFORM_OPERATOR",
      };
    else if (path === "/runtime-capabilities")
      body = { sandboxEnabled: false, workersEnabled: false };
    else if (path === "/stores")
      body = [
        {
          storeId: "store-ui",
          merchantId: "merchant-ui",
          name: "测试店铺",
          status: "ACTIVE",
          version: 1,
        },
      ];
    else if (path === "/admin/dashboard") body = null;
    else if (path === "/admin/runtime/work-types")
      body = [{ workType: "event", actions: ["RETRY", "SKIP"] }];
    else if (path === "/admin/runtime/stopped")
      body = recovered
        ? []
        : [
            {
              workType: "event",
              workId: "event-one",
              state: "ISOLATED",
              failureClass: "BUSINESS_REJECTED",
              lastError: "目标配置缺失",
              attempts: 5,
              transientAttempts: 0,
              firstFailedAt: "2026-01-01T00:00:00Z",
              lastFailedAt: "2026-01-02T00:00:00Z",
              manualRecoveries: 0,
            },
          ];
    else if (path === "/admin/runtime/recoveries" && method === "GET")
      body = recovered
        ? [
            {
              id: 1,
              actorId: "admin",
              workType: "event",
              workId: "event-one",
              action: "RETRY",
              result: "APPLIED",
              reason: "修复配置后恢复",
              createdAt: "2026-01-02T00:00:00Z",
            },
          ]
        : [];
    else if (path === "/admin/runtime/replay/classifications")
      body = [
        {
          consumer: "marketing-effects-v1",
          types: ["order.paid.v1"],
          effects: ["PURE"],
          evidence: "纯投影",
          unprocessed: {
            allowed: true,
            code: "ALLOWED",
            detail: "纯投影可补齐",
          },
          reprocess: { allowed: true, code: "ALLOWED", detail: "纯投影可重放" },
        },
      ];
    else if (path === "/admin/runtime/replays" && method === "GET")
      body = [job()];
    else if (path === "/admin/runtime/replays/replay-one") body = job();
    else if (path === "/platform/runtime")
      body = {
        observedAt: "2026-01-01T00:00:00Z",
        alerts: [],
        events: {
          health: {
            pending: 3,
            due: 1,
            isolated: 0,
            oldestDueAgeSeconds: 12,
            unrouted: 0,
          },
          skipped: 0,
        },
        lanes: {
          events: {
            rotation: {
              lastRunAt: "2026-01-01T00:00:00Z",
              completed: 1,
              breakerOpen: false,
            },
          },
        },
        replay: { executed: 0, failed: 0, blocked: 0, yielded: 0 },
        retention: {
          stats: {
            enabled: false,
            runs: 0,
            failures: 0,
            deliveredPurged: 0,
            inboxPurged: 0,
            commandsPurged: 0,
          },
          lag: [],
        },
      };
    if (method === "POST") {
      writes.push({
        path,
        body: route.request().postDataJSON(),
        key: route.request().headers()["idempotency-key"],
      });
      if (path === "/admin/runtime/recoveries") {
        if (
          options.unknown &&
          writes.filter((w) => w.path === path).length === 1
        )
          return route.fulfill({
            status: 503,
            json: { message: "回执暂不可用" },
          });
        recovered = true;
        body = {
          workType: "event",
          action: "RETRY",
          applied: 1,
          rejected: 0,
          outcomes: [
            {
              workId: "event-one",
              result: "APPLIED",
              previousState: "ISOLATED",
              newState: "PENDING",
            },
          ],
        };
      } else if (path === "/admin/runtime/replay/dry-run")
        body = {
          consumer: "marketing-effects-v1",
          mode: "UNPROCESSED",
          gate: { allowed: true, code: "ALLOWED", detail: "纯投影可补齐" },
          events: 2,
          alreadyProcessed: 1,
          wouldExecute: 1,
          capped: false,
          maxEvents: 100,
          byType: [{ eventType: "order.paid.v1", events: 2, processed: 1 }],
        };
      else if (path === "/admin/runtime/replays") body = job();
      else if (path === "/admin/runtime/replays/replay-one/control") {
        status = "PAUSED";
        body = job();
      }
    }
    await route.fulfill({ json: body });
  });
  return {
    writes,
    reads,
    identityStatus: (value: number) => {
      identityStatus = value;
    },
  };
}

async function login(page: Page, hash = "#dashboard?store=store-ui") {
  await page.goto("/" + hash);
  await page.getByLabel("访问凭据", { exact: true }).fill("fixture-credential");
  await page.getByRole("button", { name: "进入平台", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "退出", exact: true }),
  ).toBeVisible();
}

test("刷新重新核验身份并保留商品页签、门店和筛选，退出清除会话", async ({
  page,
}) => {
  const f = await fixture(page);
  await login(
    page,
    "#skus?store=store-ui&tab=product&filters=q%3Dcoffee&productAfter=spu-50",
  );
  await expect(
    page.getByRole("tab", { name: "商品资料（SPU）" }),
  ).toHaveAttribute("aria-selected", "true");
  const meReads = f.reads.filter((p) => p === "/me").length;
  await page.reload();
  await expect(
    page.getByRole("tab", { name: "商品资料（SPU）" }),
  ).toHaveAttribute("aria-selected", "true");
  expect(f.reads.filter((p) => p === "/me").length).toBeGreaterThan(meReads);
  await page
    .getByRole("tab", { name: "销售规格与上下架", exact: true })
    .click();
  await expect(page.getByLabel("商品名称或条码", { exact: true })).toHaveValue(
    "coffee",
  );
  await expect(page).toHaveURL(/productAfter=spu-50/);
  await expect(page.getByLabel("访问凭据")).toHaveCount(0);
  await page.getByRole("button", { name: "退出", exact: true }).click();
  await page.reload();
  await expect(page.getByLabel("访问凭据")).toBeVisible();
  expect(
    await page.evaluate(() => sessionStorage.getItem("commerce.session.v1")),
  ).toBeNull();
});

test("恢复登录遇到503可以重试，401清除会话和旧业务界面", async ({ page }) => {
  const f = await fixture(page);
  await login(page, "#inventory?store=store-ui");
  f.identityStatus(503);
  await page.reload();
  await expect(
    page.getByText("暂时无法恢复工作空间", { exact: true }),
  ).toBeVisible();
  f.identityStatus(200);
  await page.getByRole("button", { name: "重试恢复", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "库存额度", exact: true }),
  ).toBeVisible();
  f.identityStatus(401);
  await page.reload();
  await expect(page.getByLabel("访问凭据")).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "库存额度", exact: true }),
  ).toHaveCount(0);
  expect(
    await page.evaluate(() => sessionStorage.getItem("commerce.session.v1")),
  ).toBeNull();
});

test("平台运维仅访问平台指标与自身身份，刷新保持只读工作台", async ({
  page,
}) => {
  const f = await fixture(page, { platform: true });
  await login(page, "#platform-runtime");
  await expect(
    page.getByRole("heading", { name: "运行健康", exact: true }),
  ).toBeVisible();
  await expect(page.getByText("后台工作车道", { exact: true })).toBeVisible();
  expect(
    f.reads.some(
      (p) =>
        p === "/stores" ||
        p.startsWith("/admin/") ||
        p === "/runtime-capabilities",
    ),
  ).toBe(false);
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "运行健康", exact: true }),
  ).toBeVisible();
});

test("停止任务展示证据，未知结果冻结原意图，原键重试并读取审计", async ({
  page,
}) => {
  const f = await fixture(page, { unknown: true });
  await login(page, "#recovery?store=store-ui");
  await page.getByRole("button", { name: "查看证据", exact: true }).click();
  await expect(page.getByRole("dialog")).toContainText("目标配置缺失");
  await page.getByRole("button", { name: "返回列表", exact: true }).click();
  await page
    .getByRole("checkbox", { name: "Select row 1", exact: true })
    .check();
  await page.getByLabel("处理方式", { exact: true }).click();
  await page.getByText("恢复执行", { exact: true }).last().click();
  await page.getByLabel("处理原因", { exact: true }).fill("修复配置后恢复");
  await page.getByRole("button", { name: "确认恢复操作", exact: true }).click();
  await expect(
    page.getByText("结果待确认，原操作已保留", { exact: true }),
  ).toBeVisible();
  await expect(page.getByLabel("处理原因", { exact: true })).toBeDisabled();
  await page.getByRole("button", { name: "退出", exact: true }).click();
  await expect(page.getByRole("dialog")).toContainText("请先确认当前操作结果");
  await page.getByRole("button", { name: "返回确认结果", exact: true }).click();
  await page.getByRole("button", { name: "原样重试恢复", exact: true }).click();
  await expect(
    page.getByText("已应用 1 项，拒绝 0 项", { exact: true }),
  ).toBeVisible();
  expect(f.writes[1]).toEqual(f.writes[0]);
  await page.getByRole("tab", { name: "恢复审计", exact: true }).click();
  await expect(
    page
      .getByRole("tabpanel", { name: "恢复审计", exact: true })
      .locator("tbody"),
  ).toContainText("修复配置后恢复");
});

test("重放需先试运行，填写标识不丢安全门，修改范围重新验证，控制携带版本", async ({
  page,
}) => {
  const f = await fixture(page);
  await login(page, "#replay?store=store-ui&tab=create");
  const create = page.getByRole("button", {
    name: "创建重放任务",
    exact: true,
  });
  await expect(create).toBeDisabled();
  await page.getByLabel("消费者", { exact: true }).click();
  await page
    .locator(".ant-select-item-option-content")
    .filter({ hasText: /^marketing-effects-v1$/ })
    .click();
  await page.getByLabel("事件类型", { exact: true }).click();
  await page
    .locator(".ant-select-item-option-content")
    .filter({ hasText: /^order.paid.v1$/ })
    .click();
  await page
    .getByLabel("范围开始（本机时间）", { exact: true })
    .fill("2026-01-01T00:00");
  await page
    .getByLabel("范围结束（本机时间）", { exact: true })
    .fill("2026-01-02T00:00");
  await page.getByLabel("事件检查上限", { exact: true }).fill("100");
  await page
    .getByRole("button", { name: "验证范围与安全门", exact: true })
    .click();
  await expect(
    page.getByText("试运行通过，尚未创建任务", { exact: true }),
  ).toBeVisible();
  await page.getByLabel("任务标识", { exact: true }).fill("replay-one");
  await page.getByLabel("创建原因", { exact: true }).fill("修复投影");
  await page
    .getByRole("checkbox", { name: "已核对范围，确认按安全门允许的方式执行" })
    .check();
  await expect(create).toBeEnabled();
  await page.getByLabel("事件检查上限", { exact: true }).fill("99");
  await expect(create).toBeDisabled();
  await page.getByLabel("事件检查上限", { exact: true }).fill("100");
  await page
    .getByRole("button", { name: "验证范围与安全门", exact: true })
    .click();
  await page
    .getByRole("checkbox", { name: "已核对范围，确认按安全门允许的方式执行" })
    .check();
  await create.click();
  await expect(
    page.getByText("任务 replay-one 已受理", { exact: true }),
  ).toBeVisible();
  await page.getByRole("tab", { name: "重放任务", exact: true }).click();
  await page.getByRole("button", { name: "查看任务", exact: true }).click();
  await page.getByLabel("任务操作", { exact: true }).click();
  await page.getByText("暂停任务", { exact: true }).last().click();
  await page.getByLabel("操作原因", { exact: true }).fill("暂停检查");
  await page.getByRole("button", { name: "确认任务操作", exact: true }).click();
  await expect(
    page.getByText("任务状态已确认：PAUSED", { exact: true }),
  ).toBeVisible();
  expect(f.writes.at(-1)?.body).toEqual({
    action: "PAUSE",
    reason: "暂停检查",
    expectedVersion: 0,
  });
  await page.getByRole("button", { name: "返回列表", exact: true }).click();
  await page.getByRole("tab", { name: "消费者安全矩阵", exact: true }).click();
  const ratios = await page
    .locator(".runtime-gate-status")
    .evaluateAll((nodes) => {
      const luminance = (color: string) => {
        const rgb = color
          .match(/[\d.]+/g)!
          .slice(0, 3)
          .map(Number)
          .map((value) => {
            const channel = value / 255;
            return channel <= 0.04045
              ? channel / 12.92
              : Math.pow((channel + 0.055) / 1.055, 2.4);
          });
        return rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722;
      };
      return nodes.map((node) => {
        const style = getComputedStyle(node),
          a = luminance(style.color),
          b = luminance(style.backgroundColor);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
      });
    });
  expect(ratios.length).toBeGreaterThan(0);
  expect(ratios.every((ratio) => ratio >= 4.5)).toBe(true);
});

test("390窄屏恢复页和重放表单不溢出，字段校验与焦点可见", async ({ page }) => {
  await fixture(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await login(page, "#recovery?store=store-ui");
  await expect(
    page.getByRole("heading", { name: "任务恢复", exact: true }),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth + 1,
    ),
  ).toBe(true);
  await page.evaluate(() => {
    location.hash = "replay?store=store-ui&tab=create";
  });
  await page
    .getByRole("button", { name: "验证范围与安全门", exact: true })
    .click();
  await expect(page.getByText("请选择消费者", { exact: true })).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth + 1,
    ),
  ).toBe(true);
});

test("延迟店铺读取不能覆盖刚导航的页面、店铺和查询", async ({ page }) => {
  await fixture(page);
  let release!: () => void;
  const ready = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route("**/v1/stores*", async (route) => {
    await ready;
    await route.fulfill({
      json: [
        {
          storeId: "store-ui",
          merchantId: "merchant-ui",
          name: "测试店铺",
          status: "ACTIVE",
          version: 1,
        },
      ],
    });
  });
  await page.goto("/");
  await page.getByLabel("访问凭据", { exact: true }).fill("fixture-admin");
  await page.getByRole("button", { name: "进入平台", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "退出", exact: true }),
  ).toBeVisible();
  await page.evaluate(() => {
    location.hash =
      "skus?store=chosen-store&tab=product&productAfter=product-20";
  });
  release();
  await expect(
    page.getByRole("tab", { name: "商品资料（SPU）", exact: true }),
  ).toHaveAttribute("aria-selected", "true");
  await expect(page).toHaveURL(
    /#skus\?store=chosen-store&tab=product&productAfter=product-20$/,
  );
  await page.reload();
  await expect(
    page.getByRole("tab", { name: "商品资料（SPU）", exact: true }),
  ).toHaveAttribute("aria-selected", "true");
  await expect(page).toHaveURL(
    /#skus\?store=chosen-store&tab=product&productAfter=product-20$/,
  );
});
