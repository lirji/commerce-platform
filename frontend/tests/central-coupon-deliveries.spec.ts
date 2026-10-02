import { test, expect, type Page, type Route } from "@playwright/test";
import { mkdirSync } from "node:fs";

// 精确CouponDeliveryApi契约夹具只验证界面/客户端，不替代真实Auth岗位、原来源或MySQL效果。
const tenant = "11111111-1111-4111-8111-111111111111";
const folder = `${process.env.COMMERCE_EVIDENCE_DIR ?? "../.local/central-audiences"}/deliveries-ui`;
const centerTolerance = 24;
const content = {
  batchId: "B:" + "x".repeat(62),
  storeId: "store",
  name: "固定来源发券契约验证",
  definitionId: "D:" + "x".repeat(62),
  definitionVersion: 7,
  audience: { id: "A:" + "x".repeat(62), version: 3 },
  deadline: new Date(Date.now() + 3600000).toISOString(),
  minIntervalHours: 24,
};
const view = {
  content,
  status: "COMPLETED",
  mode: "ISSUE",
  processed: 21,
  issued: 20,
  skipped: 1,
  revoked: 0,
  kept: 0,
  cursorMember: "M21",
  revokeCursor: "",
  attempts: 0,
  errorCode: null,
  version: 22,
};
const recipient = {
  memberId: "M1",
  status: "ISSUED",
  couponId: "delivery-coupon-long-identifier-1",
  errorCode: null,
  createdAt: new Date().toISOString(),
};
async function enter(page: Page, query = "") {
  await page.goto(
    `${process.env.COMMERCE_CENTRAL_UI_URL}/operations/coupon-deliveries?tenant_id=${tenant}${query}`,
  );
  await expect(
    page.getByRole("heading", { name: "定向发券", exact: true }),
  ).toBeVisible();
}
async function hint(route: Route, action: string) {
  if (
    new URL(route.request().url()).pathname ===
    `/v1/operations/coupon-deliveries/${action}-access`
  )
    return route.fulfill({ json: { allowed: true } });
  return route.fulfill({ status: 403, json: {} });
}
async function operation(page: Page, label: string) {
  await page.getByRole("tab", { name: label, exact: true }).click();
  await page.getByRole("button", { name: label, exact: true }).click();
  return page.getByRole("dialog", { name: label, exact: true });
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
        const box = await dialog.boundingBox(),
          viewport = page.viewportSize();
        return box && viewport
          ? Math.abs(box.y + box.height / 2 - viewport.height / 2)
          : Infinity;
      })
      .toBeLessThanOrEqual(centerTolerance);
  }
  await page.screenshot({
    path: `${folder}/${name}.png`,
    fullPage: false,
    animations: "disabled",
  });
}
test.beforeEach(async ({ page }) => {
  test.skip(!process.env.COMMERCE_CENTRAL_UI_URL, "需显式独立SSO预览");
  await page.addInitScript(() => {
    const key = "oidc.user:http://127.0.0.1:18090:commerce-ui-contract";
    if (!sessionStorage.getItem(key))
      sessionStorage.setItem(
        key,
        JSON.stringify({
          access_token: "fixture-central",
          token_type: "Bearer",
          scope: "openid profile",
          profile: { sub: "employee" },
          expires_at: Math.floor(Date.now() / 1000) + 1800,
        }),
      );
  });
});

test("创建不依赖读取，脏输入与原键未知意图在页签间保留", async ({ page }) => {
  const attempts: { key?: string; body: string | null; path: string }[] = [],
    paths: string[] = [];
  await page.route("**/v1/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    paths.push(path);
    if (
      path === "/v1/admin/coupon-deliveries" &&
      route.request().method() === "POST"
    ) {
      attempts.push({
        key: route.request().headers()["idempotency-key"],
        body: route.request().postData(),
        path,
      });
      if (attempts.length === 1) return route.abort("failed");
      return route.fulfill({
        json: {
          ...view,
          content: route.request().postDataJSON(),
          status: "RUNNING",
          processed: 0,
          issued: 0,
          skipped: 0,
          cursorMember: "",
          version: 0,
        },
      });
    }
    return hint(route, "create");
  });
  await enter(page);
  const dialog = await operation(page, "创建发券批次");
  await dialog
    .getByLabel("实际批次编号", { exact: true })
    .fill(content.batchId);
  await dialog.getByLabel("批次名称", { exact: true }).fill(content.name);
  await dialog
    .getByLabel("实际门店编号", { exact: true })
    .fill(content.storeId);
  await dialog
    .getByLabel("固定券定义编号", { exact: true })
    .fill(content.definitionId);
  await dialog.getByLabel("券定义版本", { exact: true }).fill("7");
  await dialog
    .getByLabel("固定人群编号", { exact: true })
    .fill(content.audience.id);
  await dialog.getByLabel("人群快照版本", { exact: true }).fill("3");
  await screenshot(page, "create-1440");
  for (const width of [390, 320]) {
    await page.setViewportSize({ width, height: 844 });
    await expect(
      dialog.getByRole("button", { name: "确认创建发券批次", exact: true }),
    ).toBeInViewport();
    await expect
      .poll(() =>
        page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
      )
      .toBe(true);
    await screenshot(page, `create-${width}`);
  }
  await page.keyboard.press("Escape");
  await expect(
    page.getByRole("dialog", { name: "放弃未提交的发券输入？", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "继续编辑", exact: true }).click();
  await expect(dialog.getByLabel("实际批次编号", { exact: true })).toHaveValue(
    content.batchId,
  );
  await dialog
    .getByRole("button", { name: "确认创建发券批次", exact: true })
    .click();
  await expect(
    dialog.getByText("操作结果尚未确认，请保留原意图重试", { exact: true }),
  ).toBeVisible();
  await expect(dialog.getByText("保留的原输入", { exact: true })).toBeVisible();
  await expect(
    dialog.getByText(content.batchId, { exact: true }),
  ).toBeVisible();
  await screenshot(page, "create-unknown-320");
  await dialog
    .getByRole("button", { name: "返回页签（保留原意图）", exact: true })
    .click();
  await page.getByRole("button", { name: "保留并返回", exact: true }).click();
  await page.getByRole("tab", { name: "控制批次", exact: true }).click();
  await page.getByRole("button", { name: "退出登录", exact: true }).click();
  await page.getByRole("button", { name: "留在当前页", exact: true }).click();
  await page.getByRole("tab", { name: "创建发券批次", exact: true }).click();
  await page
    .getByRole("button", { name: "恢复原操作意图", exact: true })
    .click();
  await expect(
    dialog.getByLabel("实际批次编号", { exact: true }),
  ).toBeDisabled();
  await dialog
    .getByRole("button", { name: "重新核验权限", exact: true })
    .click();
  await dialog
    .getByRole("button", { name: "原样重试操作", exact: true })
    .click();
  await expect(page.getByText("批次回执已确认", { exact: true })).toBeVisible();
  await expect.poll(() => attempts.length).toBe(2);
  expect(attempts[0].key).toBeTruthy();
  expect(attempts[1]).toEqual(attempts[0]);
  expect(
    paths.some((path) =>
      /\/members|\/audiences|\/coupon-definitions/.test(path),
    ),
  ).toBe(false);
});

test("控制独立输入CAS，确定409可修正；未知后的403保留原请求", async ({
  page,
}) => {
  const attempts: { key?: string; body: string | null }[] = [];
  await page.route("**/v1/**", async (route) => {
    if (new URL(route.request().url()).pathname.endsWith("/control")) {
      attempts.push({
        key: route.request().headers()["idempotency-key"],
        body: route.request().postData(),
      });
      if (attempts.length === 1)
        return route.fulfill({ status: 409, json: {} });
      if (attempts.length === 2) return route.abort("failed");
      return route.fulfill({ status: 403, json: {} });
    }
    return hint(route, "control");
  });
  await enter(page);
  const dialog = await operation(page, "控制批次");
  // AntD虚拟列表的ARIA option并非可点击项；验证实际可见选项切换，再恢复原操作。
  for (const action of ["取消后续发放", "撤回可用券", "恢复隔离任务"]) {
    await dialog.getByLabel("控制动作", { exact: true }).click();
    await page
      .locator(".ant-select-dropdown:visible .ant-select-item-option-content")
      .filter({ hasText: action })
      .click();
    await expect(dialog.getByTitle(action, { exact: true })).toBeVisible();
  }
  await dialog
    .getByLabel("实际批次编号", { exact: true })
    .fill(content.batchId);
  await dialog.getByLabel("进度 CAS 版本", { exact: true }).fill("0");
  await dialog.getByLabel("操作原因", { exact: true }).fill("核对任务后取消");
  await screenshot(page, "control-1440");
  await dialog
    .getByRole("button", { name: "确认控制批次", exact: true })
    .click();
  await expect(
    page.getByText("批次版本或状态冲突", { exact: true }),
  ).toBeVisible();
  await dialog
    .getByRole("button", { name: "重新核验权限", exact: true })
    .click();
  await dialog.getByLabel("进度 CAS 版本", { exact: true }).fill("22");
  await dialog
    .getByRole("button", { name: "确认控制批次", exact: true })
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
  await expect(
    dialog.getByLabel("进度 CAS 版本", { exact: true }),
  ).toBeDisabled();
  await expect.poll(() => attempts.length).toBe(3);
  expect(attempts[0].key).not.toEqual(attempts[1].key);
  expect(attempts[2]).toEqual(attempts[1]);
  await screenshot(page, "control-original-denial-1440");
});

test("真实DTO固定来源与进度分开，收件人有界翻页、长编号窄屏和焦点返回", async ({
  page,
}) => {
  const paths: string[] = [];
  await page.route("**/v1/**", (route) => {
    const url = new URL(route.request().url());
    paths.push(url.pathname + url.search);
    if (url.pathname === "/v1/admin/coupon-deliveries")
      return route.fulfill({
        json: [
          {
            ...view,
            status: "REVOCATION_DONE",
            mode: "REVOKE",
            revoked: view.issued,
          },
        ],
      });
    if (url.pathname.endsWith("/recipients"))
      return route.fulfill({
        json: url.searchParams.get("after")
          ? []
          : Array.from({ length: 50 }, (_, index) => ({
              ...recipient,
              memberId: `M${String(index + 1).padStart(2, "0")}`,
            })),
      });
    return route.fulfill({ status: 403, json: {} });
  });
  await enter(page, "&Delivery.store=store");
  const trigger = page.getByRole("button", {
    name: "详情与收件人",
    exact: true,
  });
  await trigger.click();
  const dialog = page.getByRole("dialog", {
    name: "发券批次与收件人",
    exact: true,
  });
  await expect(
    dialog.getByText("进度 CAS 版本", { exact: true }),
  ).toBeVisible();
  await expect(
    dialog.getByText("固定人群 / 快照版本", { exact: true }),
  ).toBeVisible();
  await expect(dialog.getByText("发放检查点", { exact: true })).toBeVisible();
  await screenshot(page, "detail-1440");
  for (const width of [390, 320]) {
    await page.setViewportSize({ width, height: 844 });
    await expect(
      dialog.getByRole("button", { name: "关闭", exact: true }).last(),
    ).toBeInViewport();
    const id = dialog.locator(".ant-descriptions-item-content").first();
    await expect(id).toHaveText(content.batchId);
    await expect
      .poll(() =>
        id.evaluate(
          (element) => element.scrollWidth <= element.clientWidth + 1,
        ),
      )
      .toBe(true);
    const status = dialog
      .locator(".ant-tag")
      .filter({ hasText: "撤回处理完成 · REVOCATION_DONE" })
      .first();
    await expect
      .poll(() =>
        status.evaluate((element) => {
          const bounds = element.getBoundingClientRect();
          const container = element.parentElement!.getBoundingClientRect();
          return (
            element.scrollWidth <= element.clientWidth + 1 &&
            bounds.right <= container.right + 1
          );
        }),
      )
      .toBe(true);
    await screenshot(page, `detail-${width}`);
  }
  await dialog.getByRole("button", { name: "加载后续", exact: true }).click();
  await expect(
    dialog.getByText("本页 0 条，每页最多 50 条", { exact: true }),
  ).toBeVisible();
  expect(
    paths.some((path) => /recipients\?after=M50&limit=50$/.test(path)),
  ).toBe(true);
  await dialog.getByRole("button", { name: "返回首批", exact: true }).click();
  await expect(dialog.getByText("M01", { exact: true })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(dialog).not.toBeVisible();
  await expect(trigger).toBeFocused();
  expect(new URL(page.url()).searchParams.get("Delivery.store")).toBe("store");
});

test("pump未知必须核对后显式新调用，不自动重发、不伪装幂等", async ({
  page,
}) => {
  const attempts: Record<string, string>[] = [];
  await page.route("**/v1/**", (route) => {
    if (
      new URL(route.request().url()).pathname ===
      "/v1/admin/coupon-deliveries/pump"
    ) {
      attempts.push(route.request().headers());
      if (attempts.length === 1) return route.abort("failed");
      return route.fulfill({ json: 2 });
    }
    return hint(route, "pump");
  });
  await enter(page);
  await page.getByRole("tab", { name: "单次推进", exact: true }).click();
  await page.getByRole("button", { name: "执行单次推进", exact: true }).click();
  await expect(
    page.getByText("本次推进结果未知，可能已提交部分效果", { exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "重新核验单次推进权限", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "执行单次推进", exact: true }),
  ).toHaveCount(0);
  expect(attempts).toHaveLength(1);
  for (const width of [390, 320]) {
    await page.setViewportSize({ width, height: 844 });
    await screenshot(page, `pump-unknown-${width}`);
    await expect
      .poll(
        async () =>
          (
            await page
              .getByText("本次推进结果未知，可能已提交部分效果", {
                exact: true,
              })
              .boundingBox()
          )?.width ?? 0,
      )
      .toBeGreaterThan(width * 0.55);
  }
  await page
    .getByRole("button", { name: "核对后允许下一次推进", exact: true })
    .click();
  await screenshot(page, "pump-confirm-320");
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
    page.getByText("本次推进回执已确认：2", { exact: true }),
  ).toBeVisible();
  expect(attempts).toHaveLength(2);
  expect(attempts.every((headers) => !headers["idempotency-key"])).toBe(true);
});

test("客户端有限路径和输入、损坏或错来源回执拒绝确认、401卸载", async ({
  page,
}) => {
  await page.route("**/v1/**", (route) => hint(route, "create"));
  await enter(page);
  const result = await page.evaluate(
    async ({ content, view, tenant }) => {
      const path = "/src/iam/couponDeliveryClient.ts";
      const { couponDeliveryClient } = await import(/* @vite-ignore */ path);
      const original = window.fetch;
      let fetched = 0,
        expired = false;
      window.fetch = async () => {
        fetched++;
        return new Response(JSON.stringify(view), { status: 200 });
      };
      const client = couponDeliveryClient(
        { token: "fixture-central", tenant },
        () => {
          expired = true;
        },
      );
      try {
        const invalid = [
          ["/admin/coupon-deliveries?storeId=store&after=&limit=51", "GET"],
          [
            "/admin/coupon-deliveries?storeId=store&after=&limit=50&limit=50",
            "GET",
          ],
          [
            "/admin/coupon-deliveries/B/recipients?after=%2Fbad&limit=50",
            "GET",
          ],
          ["/admin/coupon-deliveries/%2Fbad/control", "POST"],
          ["/admin/coupon-deliveries/%zz/control", "POST"],
          ["/admin/coupon-deliveries/B/recipients?after=&limit=50", "POST"],
          ["/operations/coupon-deliveries/read-access", "GET"],
          ["/operations/coupon-deliveries/create-access", "POST"],
          ["https://example.invalid/admin/coupon-deliveries", "POST"],
          ["/admin/members", "GET"],
          ["/admin/coupon-deliveries/pump?limit=50", "POST"],
        ];
        const rejected: boolean[] = [];
        for (const [path, method] of invalid) {
          try {
            await client(path, {
              method,
              key: "fixed-key",
              body: method === "POST" ? content : undefined,
            });
            rejected.push(false);
          } catch (error) {
            rejected.push((error as { status?: number }).status === 403);
          }
        }
        const invalidFetches = fetched;
        let wrongTarget = false,
          wrongSource = false,
          damaged = false,
          invalidInput = false;
        try {
          await client("/admin/coupon-deliveries", {
            method: "POST",
            key: "k",
            body: { ...content, batchId: "DIFFERENT" },
          });
        } catch {
          wrongTarget = true;
        }
        try {
          await client("/admin/coupon-deliveries", {
            method: "POST",
            key: "k",
            body: { ...content, audience: { ...content.audience, version: 4 } },
          });
        } catch {
          wrongSource = true;
        }
        try {
          await client("/admin/coupon-deliveries/B/control", {
            method: "POST",
            key: "k",
            body: { action: "UNSUPPORTED", expectedVersion: 0, reason: "r" },
          });
        } catch (error) {
          invalidInput = (error as { status?: number }).status === 400;
        }
        window.fetch = async () =>
          new Response(JSON.stringify([{ ...view, processed: -1 }]), {
            status: 200,
          });
        try {
          await client(
            "/admin/coupon-deliveries?storeId=store&after=&limit=50",
          );
        } catch {
          damaged = true;
        }
        window.fetch = async () => new Response("{}", { status: 401 });
        try {
          await client("/operations/coupon-deliveries/create-access");
        } catch {
          /* 当前界面应卸载。 */
        }
        return {
          rejected,
          invalidFetches,
          wrongTarget,
          wrongSource,
          damaged,
          invalidInput,
          expired,
        };
      } finally {
        window.fetch = original;
      }
    },
    { content, view, tenant },
  );
  expect(result.rejected.every(Boolean)).toBe(true);
  expect(result.invalidFetches).toBe(0);
  expect(
    result.wrongTarget &&
      result.wrongSource &&
      result.damaged &&
      result.invalidInput &&
      result.expired,
  ).toBe(true);
});

test("503关闭资格且不降级身份，401清空实际显示的批次与弹层", async ({
  page,
}) => {
  let unavailable = true,
    expired = false;
  const posts: string[] = [];
  await page.route("**/v1/**", (route) => {
    const path = new URL(route.request().url()).pathname;
    if (route.request().method() === "POST") posts.push(path);
    if (expired) return route.fulfill({ status: 401, json: {} });
    if (path === "/v1/operations/coupon-deliveries/create-access")
      return route.fulfill({
        status: unavailable ? 503 : 200,
        json: unavailable ? {} : { allowed: true },
      });
    if (path === "/v1/admin/coupon-deliveries")
      return route.fulfill({ json: [view] });
    if (path.endsWith("/recipients"))
      return route.fulfill({ json: [recipient] });
    return route.fulfill({ status: 403, json: {} });
  });
  await enter(page, "&Delivery.store=store");
  await page.getByRole("tab", { name: "创建发券批次", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "创建发券批次", exact: true }),
  ).toHaveCount(0);
  unavailable = false;
  await page
    .getByRole("button", { name: "重新核验创建发券批次权限", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "创建发券批次", exact: true }),
  ).toBeVisible();
  expect(posts).toEqual([]);
  await page.getByRole("tab", { name: "批次与收件人", exact: true }).click();
  await page.getByRole("button", { name: "详情与收件人", exact: true }).click();
  const dialog = page.getByRole("dialog", {
    name: "发券批次与收件人",
    exact: true,
  });
  await expect(
    dialog.getByText(recipient.memberId, { exact: true }),
  ).toBeVisible();
  expired = true;
  await dialog
    .getByRole("button", { name: "刷新收件人记录", exact: true })
    .click();
  await expect(
    page.getByText("登录已失效，请重新登录", { exact: true }),
  ).toBeVisible();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page.getByText(content.batchId, { exact: true })).toHaveCount(0);
});

test("提交中不能关闭或重复发送，确定成功后再恢复离开", async ({ page }) => {
  let finish: (() => void) | undefined;
  let posts = 0;
  const released = new Promise<void>((resolve) => {
    finish = resolve;
  });
  await page.route("**/v1/**", async (route) => {
    if (new URL(route.request().url()).pathname.endsWith("/control")) {
      posts++;
      await released;
      return route.fulfill({
        json: { ...view, status: "CANCELLED", version: 23 },
      });
    }
    return hint(route, "control");
  });
  await enter(page);
  const dialog = await operation(page, "控制批次");
  await dialog
    .getByLabel("实际批次编号", { exact: true })
    .fill(content.batchId);
  await dialog.getByLabel("进度 CAS 版本", { exact: true }).fill("22");
  await dialog.getByLabel("操作原因", { exact: true }).fill("停止后续发放");
  await dialog
    .getByRole("button", { name: "确认控制批次", exact: true })
    .click();
  await expect.poll(() => posts).toBe(1);
  await expect(
    dialog.getByRole("button", { name: "取消", exact: true }),
  ).toBeDisabled();
  await expect(dialog.locator(".ant-modal-close")).toHaveCount(0);
  await page.keyboard.press("Escape");
  await expect(dialog).toBeVisible();
  await screenshot(page, "control-busy-1440");
  finish!();
  await expect(dialog).not.toBeVisible();
  await expect(page.getByText("批次回执已确认", { exact: true })).toBeVisible();
  expect(posts).toBe(1);
});

test("具备多个岗位时，关闭后再打开不同表单的标签与目标保持独立", async ({
  page,
}) => {
  await page.route("**/v1/**", (route) => {
    if (new URL(route.request().url()).pathname.includes("-access"))
      return route.fulfill({ json: { allowed: true } });
    return route.fulfill({ status: 403, json: {} });
  });
  await enter(page);
  let dialog = await operation(page, "创建发券批次");
  await dialog
    .getByLabel("实际批次编号", { exact: true })
    .fill("CREATE-TARGET");
  await dialog.getByRole("button", { name: "取消", exact: true }).click();
  await page.getByRole("button", { name: "放弃输入", exact: true }).click();
  await expect(dialog).not.toBeVisible();
  dialog = await operation(page, "控制批次");
  await dialog
    .getByLabel("实际批次编号", { exact: true })
    .fill("CONTROL-TARGET");
  await expect(dialog.getByLabel("实际批次编号", { exact: true })).toHaveValue(
    "CONTROL-TARGET",
  );
});
