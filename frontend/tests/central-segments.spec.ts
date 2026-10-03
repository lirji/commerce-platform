import { test, expect, type Page, type Route } from "@playwright/test";
import { mkdirSync } from "node:fs";

// 公开SegmentApi DTO只用于界面/原意图测试；真实Auth、SQL与最终JAR另由跨进程验证。
const tenant = "11111111-1111-4111-8111-111111111111";
const folder = `${process.env.COMMERCE_EVIDENCE_DIR ?? "../.local/central-audiences"}/segments-ui`;
const dialogCenterTolerancePx = 24;
const view = {
  content: {
    segmentId: "SEG:7",
    version: 7,
    name: "动态人群契约验证",
    rule: {
      kind: "COMPARE",
      field: "memberLevel",
      operator: "EQ",
      valueType: "TEXT",
      value: "BASIC",
    },
    ttlSeconds: 3600,
    refreshSeconds: 60,
    maxMembers: 1000,
  },
  audienceId: "dyn-" + "a".repeat(40),
  enabled: false,
  lockVersion: 3,
};
const run = {
  runId: "RUN:7",
  segmentId: "SEG:7",
  definitionVersion: 7,
  audienceId: "dyn-" + "a".repeat(40),
  snapshotVersion: 2,
  cursorMember: "M100",
  processed: 100,
  matched: 90,
  status: "RUNNING",
  attempts: 0,
  errorCode: null,
  startedAt: "2026-10-01T00:00:00Z",
  validUntil: "2026-10-01T01:00:00Z",
  availableAt: "2026-10-01T00:00:00Z",
  entriesAnnounced: false,
  entryAttempts: 0,
};
async function enter(page: Page, query = "") {
  await page.goto(
    `${process.env.COMMERCE_CENTRAL_UI_URL}/operations/segments?tenant_id=${tenant}${query}`,
  );
  await expect(
    page.getByRole("heading", { name: "动态人群", exact: true }),
  ).toBeVisible();
}
async function screenshot(page: Page, name: string) {
  mkdirSync(folder, { recursive: true });
  await page.evaluate(async () => {
    await document.fonts.ready;
    await new Promise((resolve) =>
      requestAnimationFrame(() => requestAnimationFrame(resolve)),
    );
  });
  const dialog = page.getByRole("dialog").last();
  if (await dialog.count()) {
    await expect
      .poll(async () => {
        const box = await dialog.boundingBox();
        const viewport = page.viewportSize();
        return box && viewport
          ? Math.abs(box.y + box.height / 2 - viewport.height / 2)
          : Number.POSITIVE_INFINITY;
      })
      .toBeLessThanOrEqual(dialogCenterTolerancePx);
  }
  await page.screenshot({
    path: `${folder}/${name}.png`,
    fullPage: false,
    animations: "disabled",
  });
}
async function onlyHint(route: Route, action: string) {
  if (
    new URL(route.request().url()).pathname ===
    `/v1/operations/segments/${action}-access`
  )
    return route.fulfill({ json: { allowed: true } });
  return route.fulfill({ status: 403, json: {} });
}
async function openOperation(page: Page, action: string) {
  await page.getByRole("tab", { name: "独立操作", exact: true }).click();
  await page.getByRole("tab", { name: action, exact: true }).click();
  await page.getByRole("button", { name: action, exact: true }).click();
  return page.getByRole("dialog", { name: action, exact: true });
}
test.beforeEach(async ({ page }) => {
  test.skip(!process.env.COMMERCE_CENTRAL_UI_URL, "需显式独立SSO预览");
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
});

test("创建独立于读取，手机表单、关闭保护和丢响应原意图恢复", async ({
  page,
}) => {
  const errors: string[] = [],
    attempts: { key?: string; body: string | null }[] = [],
    paths: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.route("**/v1/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    paths.push(path);
    if (path === "/v1/admin/segments" && route.request().method() === "POST") {
      attempts.push({
        key: route.request().headers()["idempotency-key"],
        body: route.request().postData(),
      });
      if (attempts.length === 1) return route.abort("failed");
      return route.fulfill({ json: view });
    }
    return onlyHint(route, "create");
  });
  await enter(page);
  await page.getByRole("tab", { name: "创建定义", exact: true }).click();
  await page.getByRole("button", { name: "创建定义", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "创建定义", exact: true });
  await dialog.getByLabel("定义编号", { exact: true }).fill("SEG:7");
  await dialog.getByLabel("定义版本（不可变）", { exact: true }).fill("7");
  await dialog.getByLabel("人群名称", { exact: true }).fill(view.content.name);
  await dialog
    .getByRole("textbox", { name: "比较值", exact: true })
    .fill("BASIC");
  await screenshot(page, "create-1440");
  for (const width of [390, 320]) {
    await page.setViewportSize({ width, height: 844 });
    await expect
      .poll(() =>
        page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
      )
      .toBe(true);
    await expect(
      dialog.getByRole("button", { name: "确认创建定义", exact: true }),
    ).toBeInViewport();
    await screenshot(page, `create-${width}`);
  }
  await page.keyboard.press("Escape");
  await expect(
    page.getByRole("dialog", {
      name: "放弃未提交的动态人群输入？",
      exact: true,
    }),
  ).toBeVisible();
  await screenshot(page, "dirty-close-320");
  await page.getByRole("button", { name: "继续编辑", exact: true }).click();
  await expect(dialog.getByLabel("定义编号", { exact: true })).toHaveValue(
    "SEG:7",
  );
  await dialog
    .getByRole("button", { name: "确认创建定义", exact: true })
    .click();
  await expect(
    dialog.getByText("操作结果尚未确认，请保留原意图重试", { exact: true }),
  ).toBeVisible();
  await screenshot(page, "create-unknown-320");
  await dialog
    .getByRole("button", { name: "返回页签（保留原意图）", exact: true })
    .click();
  await page.getByRole("button", { name: "保留并返回", exact: true }).click();
  await page.getByRole("tab", { name: "独立操作", exact: true }).click();
  await page.getByRole("button", { name: "退出登录", exact: true }).click();
  await page.getByRole("button", { name: "留在当前页", exact: true }).click();
  await page.getByRole("tab", { name: "创建定义", exact: true }).click();
  await page
    .getByRole("button", { name: "重新核验创建定义权限", exact: true })
    .click();
  await page
    .getByRole("button", { name: "恢复原操作意图", exact: true })
    .click();
  await expect(dialog.getByLabel("定义编号", { exact: true })).toBeDisabled();
  await dialog
    .getByRole("button", { name: "原样重试操作", exact: true })
    .click();
  await expect(
    page.getByText("定义或调度结果已确认", { exact: true }),
  ).toBeVisible();
  expect(attempts).toHaveLength(2);
  expect(attempts[0].key).toBeTruthy();
  await expect.poll(() => attempts.length).toBe(2);
  expect(attempts[1]).toEqual(attempts[0]);
  expect(paths.some((path) => /\/members|\/rules|\/audiences/.test(path))).toBe(
    false,
  );
  expect(errors).toEqual([]);
});

test("仅调度权限直接输入真实目标，409后允许修正锁版本", async ({ page }) => {
  const attempts: { key?: string; body: string | null }[] = [];
  await page.route("**/v1/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/v1/admin/segments/SEG%3A7/schedule") {
      attempts.push({
        key: route.request().headers()["idempotency-key"],
        body: route.request().postData(),
      });
      if (attempts.length === 1)
        return route.fulfill({ status: 409, json: {} });
      return route.fulfill({
        json: { ...view, lockVersion: 4, enabled: true },
      });
    }
    return onlyHint(route, "schedule");
  });
  await enter(page);
  let dialog = await openOperation(page, "调整调度");
  await dialog.getByLabel("实际定义编号", { exact: true }).fill("SEG:7");
  await dialog.getByLabel("调度锁版本", { exact: true }).fill("0");
  await dialog.getByRole("switch").click();
  await screenshot(page, "schedule-1440");
  await dialog
    .getByRole("button", { name: "确认调整调度", exact: true })
    .click();
  await expect(
    page.getByText("目标版本或任务状态冲突", { exact: true }),
  ).toBeVisible();
  await dialog
    .getByRole("button", { name: "重新核验权限", exact: true })
    .click();
  await expect(dialog.getByLabel("调度锁版本", { exact: true })).toBeEnabled();
  await dialog.getByLabel("调度锁版本", { exact: true }).fill("3");
  await dialog
    .getByRole("button", { name: "确认调整调度", exact: true })
    .click();
  await expect(
    page.getByText("定义或调度结果已确认", { exact: true }),
  ).toBeVisible();
  expect(attempts).toHaveLength(2);
  expect(attempts[0].key).not.toEqual(attempts[1].key);
  expect(JSON.parse(attempts[1].body!)).toEqual({
    expectedVersion: 3,
    enabled: true,
  });
});

test("读取真实DTO分开三个版本，运行详情键盘与焦点返回", async ({ page }) => {
  await page.route("**/v1/**", (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/v1/admin/segments") return route.fulfill({ json: [view] });
    if (path === "/v1/admin/segments/SEG%3A7/runs")
      return route.fulfill({ json: [run] });
    return route.fulfill({ status: 403, json: {} });
  });
  await enter(page);
  await screenshot(page, "directory-1440");
  const open = page.getByRole("button", { name: "详情与记录", exact: true });
  await open.click();
  const definition = page.getByRole("dialog", {
    name: "动态人群定义与运行记录",
    exact: true,
  });
  await expect(
    definition.getByText("调度锁版本", { exact: true }),
  ).toBeVisible();
  await expect(definition.getByText("RUN:7", { exact: true })).toBeVisible();
  await screenshot(page, "definition-1440");
  await definition.getByRole("button", { name: "查看", exact: true }).click();
  const detail = page.getByRole("dialog", {
    name: "动态人群运行详情",
    exact: true,
  });
  await expect(detail.getByText("输出快照版本", { exact: true })).toBeVisible();
  await expect(detail.getByText("尚未完成", { exact: true })).toBeVisible();
  await screenshot(page, "run-1440");
  for (const width of [390, 320]) {
    await page.setViewportSize({ width, height: 844 });
    await expect
      .poll(() =>
        page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
      )
      .toBe(true);
    await expect(
      detail.getByRole("button", { name: "关闭", exact: true }).last(),
    ).toBeInViewport();
    await screenshot(page, `run-${width}`);
  }
  await page.keyboard.press("Escape");
  await expect(detail).not.toBeVisible();
  await page.keyboard.press("Escape");
  await expect(definition).not.toBeVisible();
  await expect(open).toBeFocused();
  expect(new URL(page.url()).searchParams.get("tenant_id")).toEqual(tenant);
});

test("pump未知不会自动重发或伪装幂等，显式确认才允许下一次", async ({
  page,
}) => {
  const attempts: Record<string, string>[] = [];
  await page.route("**/v1/**", async (route) => {
    if (new URL(route.request().url()).pathname === "/v1/admin/segments/pump") {
      attempts.push(route.request().headers());
      if (attempts.length === 1) return route.abort("failed");
      return route.fulfill({ json: 1 });
    }
    return onlyHint(route, "pump");
  });
  await enter(page);
  await page.getByRole("tab", { name: "独立操作", exact: true }).click();
  await page.getByRole("tab", { name: "单次推进", exact: true }).click();
  await page.getByRole("button", { name: "执行单次推进", exact: true }).click();
  await expect(
    page.getByText("本次推进结果未知，可能已提交部分批次", { exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "重新核验单次推进权限", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "执行单次推进", exact: true }),
  ).toHaveCount(0);
  expect(attempts).toHaveLength(1);
  // 无横向溢出仍可能把正文挤成单字竖排；检查实际文字宽度和按钮可达性。
  for (const width of [390, 320]) {
    await page.setViewportSize({ width, height: 844 });
    const notice = page.getByText("本次推进结果未知，可能已提交部分批次", {
      exact: true,
    });
    const confirm = page.getByRole("button", {
      name: "核对后允许下一次推进",
      exact: true,
    });
    await notice.scrollIntoViewIfNeeded();
    await confirm.scrollIntoViewIfNeeded();
    await expect(notice).toBeInViewport();
    await expect(confirm).toBeInViewport();
    await expect
      .poll(async () => (await notice.boundingBox())?.width ?? 0)
      .toBeGreaterThanOrEqual(width * 0.55);
    await screenshot(page, `pump-unknown-notice-${width}`);
    expect(attempts).toHaveLength(1);
  }
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page
    .getByRole("button", { name: "核对后允许下一次推进", exact: true })
    .click();
  await screenshot(page, "pump-unknown-confirm-1440");
  await page.getByRole("button", { name: "暂不推进", exact: true }).click();
  expect(attempts).toHaveLength(1);
  await page
    .getByRole("button", { name: "核对后允许下一次推进", exact: true })
    .click();
  await page
    .getByRole("button", { name: "已核对，允许下一次", exact: true })
    .click();
  await page.getByRole("button", { name: "执行单次推进", exact: true }).click();
  await expect(
    page.getByText("本次推进回执已确认：1", { exact: true }),
  ).toBeVisible();
  expect(attempts).toHaveLength(2);
  expect(attempts.every((headers) => !headers["idempotency-key"])).toBe(true);
});

test("刷新受理不等于完成，实际原来源拒绝时保留未知控制意图，401清空敏感界面", async ({
  page,
}) => {
  let control = 0,
    expired = false;
  const attempts: { path: string; key?: string; body: string | null }[] = [];
  await page.route("**/v1/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (expired) return route.fulfill({ status: 401, json: {} });
    if (path.endsWith("refresh-access") || path.endsWith("control-access"))
      return route.fulfill({ json: { allowed: true } });
    if (path === "/v1/admin/segments/SEG%3A7/refresh")
      return route.fulfill({ json: run });
    if (path === "/v1/admin/segment-runs/RUN%3A7/cancel") {
      attempts.push({
        path,
        key: route.request().headers()["idempotency-key"],
        body: route.request().postData(),
      });
      if (++control === 1) return route.abort("failed");
      return route.fulfill({ status: 403, json: {} });
    }
    return route.fulfill({ status: 403, json: {} });
  });
  await enter(page);
  let dialog = await openOperation(page, "发起刷新");
  await dialog.getByLabel("实际定义编号", { exact: true }).fill("SEG:7");
  await dialog
    .getByRole("button", { name: "确认发起刷新", exact: true })
    .click();
  await expect(page.getByText("任务回执已确认", { exact: true })).toBeVisible();
  await expect(page.getByText("RUNNING", { exact: true })).toBeVisible();
  await screenshot(page, "refresh-receipt-1440");
  dialog = await openOperation(page, "控制任务");
  await dialog.getByLabel("实际运行编号", { exact: true }).fill("RUN:7");
  await dialog
    .getByRole("button", { name: "确认控制任务", exact: true })
    .click();
  await expect(
    dialog.getByText("操作结果尚未确认，请保留原意图重试", { exact: true }),
  ).toBeVisible();
  await dialog
    .getByRole("button", { name: "重新核验权限", exact: true })
    .click();
  await dialog
    .getByRole("button", { name: "原样重试操作", exact: true })
    .click();
  await expect(
    dialog.getByText("操作结果尚未确认，请保留原意图重试", { exact: true }),
  ).toBeVisible();
  await expect.poll(() => attempts.length).toBe(2);
  expect(attempts[1]).toEqual(attempts[0]);
  await screenshot(page, "control-original-denial-1440");
  expired = true;
  await dialog
    .getByRole("button", { name: "重新核验权限", exact: true })
    .click();
  await expect(
    page.getByText("登录已失效，请重新登录", { exact: true }),
  ).toBeVisible();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page.getByText("RUN:7", { exact: true })).toHaveCount(0);
});

test("严格入口拒绝方法/路径/游标越界，损坏或错目标回执不确认成功", async ({
  page,
}) => {
  await page.route("**/v1/**", (route) => onlyHint(route, "create"));
  await enter(page);
  const result = await page.evaluate(
    async ({ view, run, tenant }) => {
      const modulePath = "/src/iam/segmentClient.ts";
      const { segmentClient } = await import(/* @vite-ignore */ modulePath);
      const original = window.fetch;
      let fetched = 0,
        expired = false;
      window.fetch = async () => {
        fetched++;
        return new Response(JSON.stringify(view), { status: 200 });
      };
      const client = segmentClient({ token: "fixture-central", tenant }, () => {
        expired = true;
      });
      const invalid = [
        ["/admin/segments?after=&limit=51", "GET"],
        ["/admin/segments?after=&limit=50&limit=50", "GET"],
        ["/admin/segments?after=&limit=10&enabled=no", "GET"],
        ["/admin/segments?after=&limit=10&status=ACTIVE", "GET"],
        ["/admin/segments?after=&limit=10&unknown=value", "GET"],
        [`/admin/segments?after=&limit=10&q=${"x".repeat(65)}`, "GET"],
        ["https://example.invalid/admin/segments?after=&limit=10", "GET"],
        ["/admin/segments/S/runs?after=%2Fescape&limit=50", "GET"],
        ["/admin/segments/S/runs?after=&limit=50", "POST"],
        ["/operations/segments/read-access", "GET"],
        ["/operations/segments/create-access", "POST"],
        ["/admin/segment-runs/R/unknown", "POST"],
        ["/admin/segments/%2Fescape/refresh", "POST"],
        ["/admin/segments/%zz/refresh", "POST"],
        ["https://example.invalid/admin/segments", "POST"],
        ["/admin/members", "POST"],
      ];
      const rejected: boolean[] = [];
      try {
        for (const [path, method] of invalid) {
          try {
            await client(path, { method });
            rejected.push(false);
          } catch (error) {
            rejected.push((error as { status?: number }).status === 403);
          }
        }
        const invalidFetches = fetched;
        let filteredUrl = "";
        window.fetch = async (input) => {
          filteredUrl = String(input);
          return new Response("[]", { status: 200 });
        };
        await client("/admin/segments?after=&limit=10&q=VIP%25_&enabled=false");
        window.fetch = async () =>
          new Response(JSON.stringify(view), { status: 200 });
        let wrongTarget = false,
          damaged = false,
          invalidRule = false;
        try {
          await client("/admin/segments", {
            method: "POST",
            body: { ...view.content, segmentId: "DIFFERENT" },
          });
        } catch {
          wrongTarget = true;
        }
        window.fetch = async () =>
          new Response(JSON.stringify({ ...run, definitionVersion: 0 }), {
            status: 200,
          });
        try {
          await client("/admin/segments/SEG%3A7/refresh", { method: "POST" });
        } catch {
          damaged = true;
        }
        window.fetch = async () =>
          new Response(
            JSON.stringify({
              ...view,
              content: {
                ...view.content,
                rule: { kind: "ALL", children: ["bad-child"] },
              },
            }),
            { status: 200 },
          );
        try {
          await client("/admin/segments?after=&limit=50");
        } catch {
          invalidRule = true;
        }
        window.fetch = async () => new Response("{}", { status: 401 });
        try {
          await client("/operations/segments/create-access");
        } catch {
          /* 401必须隐藏当前工作区。 */
        }
        return {
          rejected,
          invalidFetches,
          filteredUrl,
          wrongTarget,
          damaged,
          invalidRule,
          expired,
        };
      } finally {
        window.fetch = original;
      }
    },
    { view, run, tenant },
  );
  expect(result.rejected.every(Boolean)).toBe(true);
  expect(result.invalidFetches).toBe(0);
  expect(result.filteredUrl).toContain("limit=10&q=VIP%25_&enabled=false");
  expect(result.wrongTarget).toBe(true);
  expect(result.damaged).toBe(true);
  expect(result.invalidRule).toBe(true);
  expect(result.expired).toBe(true);
});

test("资格503关闭写入，重新核验恢复且不回退旧ADMIN", async ({ page }) => {
  let unavailable = true;
  const posts: string[] = [];
  await page.route("**/v1/**", (route) => {
    if (route.request().method() === "POST") posts.push(route.request().url());
    if (
      new URL(route.request().url()).pathname ===
      "/v1/operations/segments/create-access"
    )
      return route.fulfill({
        status: unavailable ? 503 : 200,
        json: unavailable ? {} : { allowed: true },
      });
    return route.fulfill({ status: 403, json: {} });
  });
  await enter(page);
  await page.getByRole("tab", { name: "创建定义", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "创建定义", exact: true }),
  ).toHaveCount(0);
  await screenshot(page, "create-qualification-503-1440");
  unavailable = false;
  await page
    .getByRole("button", { name: "重新核验创建定义权限", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "创建定义", exact: true }),
  ).toBeVisible();
  expect(posts).toEqual([]);
});
