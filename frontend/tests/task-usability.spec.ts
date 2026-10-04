import { expect, test, type Page } from "@playwright/test";
import { mkdirSync } from "node:fs";
import { assertButtonSizes, assertCentered } from "./presentation";

// 仅在浏览器测试边界返回正式DTO；不创建产品内演示数据或伪装数据库/SSO验收。
async function openMembers(page: Page) {
  const reads: URL[] = [];
  let status = 200;
  await page.route("**/v1/**", async (route) => {
    const url = new URL(route.request().url());
    expect(route.request().method()).toBe("GET");
    let body: unknown = [];
    if (url.pathname === "/v1/me")
      body = { tenantId: "task-ui", actorId: "admin", role: "ADMIN" };
    if (url.pathname === "/v1/stores")
      body = [
        {
          storeId: "store-task",
          merchantId: "merchant-task",
          name: "验收门店",
          status: "ACTIVE",
          version: 0,
        },
      ];
    if (url.pathname === "/v1/runtime-capabilities")
      body = { sandboxEnabled: false, workersEnabled: false };
    if (url.pathname === "/v1/admin/ops-pages") {
      reads.push(url);
      body = [
        {
          content: {
            pageId: "page-task",
            storeId: "store-task",
            version: 1,
            title: "会员营销运营页面",
            sections: [],
            actions: [],
          },
          status: "DRAFT",
          lockVersion: 1,
        },
      ];
    }
    if (url.pathname === "/v1/admin/members") {
      reads.push(url);
      if (status !== 200)
        return route.fulfill({
          status,
          json: {
            message: "会员查询暂不可用",
            traceId: "trace-" + "long".repeat(30),
          },
        });
      body = [
        {
          memberId: "member-task",
          actorId: "actor-task",
          displayName: "真实契约测试会员",
          memberLevel: "VIP",
          status: "ACTIVE",
          version: 0,
        },
      ];
    }
    await route.fulfill({ json: body });
  });
  await page.goto("/#members?store=store-task");
  await page
    .getByLabel("访问凭据", { exact: true })
    .fill("test-boundary-token");
  await page.getByRole("button", { name: "进入平台", exact: true }).click();
  await expect(
    page.getByText("真实契约测试会员", { exact: true }),
  ).toBeVisible();
  return {
    reads,
    fail: (value: number) => {
      status = value;
    },
  };
}

test("草稿提示不触发读取，逐项清除保留其他条件，默认重置也清除草稿", async ({
  page,
}) => {
  const fixture = await openMembers(page);
  const before = fixture.reads.length;
  await page.getByLabel("关键词", { exact: true }).fill("会员");
  await expect(page.getByRole("status")).toContainText(
    "筛选已修改，点击查询后生效",
  );
  expect(fixture.reads.length).toBe(before);
  await page.getByLabel("状态", { exact: true }).click();
  await page
    .locator(".ant-select-item-option-content")
    .getByText("可用", { exact: true })
    .click();
  await page.getByRole("button", { name: "查询", exact: true }).click();
  await expect(page.locator(".query-pending")).toBeEmpty();
  await expect
    .poll(() => fixture.reads.at(-1)?.searchParams.get("q"))
    .toBe("会员");
  await page
    .getByRole("button", { name: "清除关键词筛选", exact: true })
    .focus();
  await page.keyboard.press("Enter");
  await expect
    .poll(() => fixture.reads.at(-1)?.searchParams.has("q"))
    .toBe(false);
  expect(fixture.reads.at(-1)?.searchParams.get("status")).toBe("ACTIVE");
  expect(fixture.reads.at(-1)?.searchParams.get("after")).toBe("");
  await expect(page.getByLabel("关键词", { exact: true })).toHaveValue("");
  await page.getByRole("button", { name: "清除状态筛选", exact: true }).click();
  await expect
    .poll(() => fixture.reads.at(-1)?.searchParams.has("status"))
    .toBe(false);
  await page.getByLabel("关键词", { exact: true }).fill("尚未查询的输入");
  await page.getByRole("button", { name: "重置筛选", exact: true }).click();
  await expect(page.getByLabel("关键词", { exact: true })).toHaveValue("");
  await expect(page.locator(".query-pending")).toBeEmpty();
  await page.reload();
  await expect(
    page.getByText("真实契约测试会员", { exact: true }),
  ).toBeVisible();
  await expect(page.getByLabel("关键词", { exact: true })).toHaveValue("");
});

test("低代码页面使用真实查询条件，窄屏宽表保留名称与操作", async ({ page }) => {
  const fixture = await openMembers(page);
  await page.goto("/#pages?store=store-task");
  await expect(
    page.getByRole("heading", { name: "低代码运营页面", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("会员营销运营页面", { exact: true }),
  ).toBeVisible();
  await page.getByLabel("关键词", { exact: true }).fill("会员营销");
  await page.getByRole("button", { name: "查询", exact: true }).click();
  await expect
    .poll(() => fixture.reads.at(-1)?.searchParams.get("q"))
    .toBe("会员营销");
  expect(fixture.reads.at(-1)?.pathname).toBe("/v1/admin/ops-pages");
  expect(fixture.reads.at(-1)?.searchParams.get("after")).toBe("");
  await page.setViewportSize({ width: 320, height: 844 });
  await expect
    .poll(() =>
      page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth + 1,
      ),
    )
    .toBe(true);
  await expect(
    page.getByRole("region", { name: "列表完整字段" }),
  ).toBeVisible();
  await assertButtonSizes(page);
  await page.reload();
  await expect(page.getByLabel("关键词", { exact: true })).toHaveValue(
    "会员营销",
  );
});

test("读取503可就地重试且保留未查询输入，权限拒绝不显示重试", async ({
  page,
}) => {
  const fixture = await openMembers(page);
  const folder = `${process.env.COMMERCE_EVIDENCE_DIR ?? "../.local/frontend-task-usability"}/new-interactions`;
  mkdirSync(folder, { recursive: true });
  await page.setViewportSize({ width: 320, height: 844 });
  await page.getByLabel("关键词", { exact: true }).fill("保留输入");
  fixture.fail(503);
  await page.getByRole("button", { name: "刷新", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "重试加载", exact: true }),
  ).toBeVisible();
  await expect(page.locator(".error-notice")).toContainText(
    "当前筛选和输入会保留",
  );
  await assertButtonSizes(page);
  await expect
    .poll(() =>
      page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth + 1,
      ),
    )
    .toBe(true);
  await page.screenshot({
    path: `${folder}/read-failure-320.png`,
    fullPage: true,
  });
  fixture.fail(200);
  await page.getByRole("button", { name: "重试加载", exact: true }).click();
  await expect(
    page.getByText("真实契约测试会员", { exact: true }),
  ).toBeVisible();
  await expect(page.getByLabel("关键词", { exact: true })).toHaveValue(
    "保留输入",
  );
  fixture.fail(403);
  await page.getByRole("button", { name: "刷新", exact: true }).click();
  await expect(
    page.getByText("当前身份没有此操作权限。", { exact: false }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "重试加载", exact: true }),
  ).toHaveCount(0);
  fixture.fail(401);
  await page.getByRole("button", { name: "刷新", exact: true }).click();
  await expect(page.getByLabel("访问凭据", { exact: true })).toBeVisible();
  await expect(page.getByRole("main", { name: "业务内容" })).toHaveCount(0);
});

test("跳过导航保留URL，宽表可键盘滚动，行按钮方向键不滚动，弹层返回焦点", async ({
  page,
}) => {
  await openMembers(page);
  const folder = `${process.env.COMMERCE_EVIDENCE_DIR ?? "../.local/frontend-task-usability"}/new-interactions`;
  mkdirSync(folder, { recursive: true });
  for (const width of [1440, 390, 320]) {
    await page.setViewportSize({ width, height: width === 1440 ? 1000 : 844 });
    const before = page.url();
    const skip = page.getByRole("link", { name: "跳到业务内容", exact: true });
    await skip.focus();
    await expect(skip).toBeInViewport();
    await page.keyboard.press("Enter");
    await expect(page.getByRole("main", { name: "业务内容" })).toBeFocused();
    expect(page.url()).toBe(before);
    await assertButtonSizes(page);
    await expect
      .poll(() =>
        page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth + 1,
        ),
      )
      .toBe(true);
    await page.screenshot({
      path: `${folder}/members-${width}.png`,
      fullPage: true,
    });
  }
  const table = page.getByRole("region", { name: "列表完整字段", exact: true });
  await expect(table).toHaveAttribute("tabindex", "0");
  await table.focus();
  const scroll = () =>
    table.evaluate((el) =>
      Math.max(
        el.scrollLeft,
        ...Array.from(
          el.querySelectorAll(".ant-table-content, .ant-table-body"),
          (node) => node.scrollLeft,
        ),
      ),
    );
  await page.keyboard.press("ArrowRight");
  await expect.poll(scroll).toBeGreaterThan(0);
  await page.keyboard.press("ArrowLeft");
  await expect.poll(scroll).toBe(0);
  const more = page.getByRole("button", { name: "更多", exact: true }).first();
  await more.focus();
  const position = await scroll();
  await page.keyboard.press("ArrowLeft");
  expect(await scroll()).toBe(position);
  await more.click();
  await page.getByRole("menuitem", { name: "档案记录", exact: true }).click();
  await assertCentered(page);
  await assertButtonSizes(page);
  await page
    .getByRole("dialog")
    .screenshot({ path: `${folder}/member-details-320.png` });
  await page.getByRole("button", { name: "返回列表", exact: true }).click();
  await expect(more).toBeFocused();
  await page.setViewportSize({ width: 1440, height: 1000 });
  await expect(page.locator(".list-table")).not.toHaveAttribute(
    "tabindex",
    "0",
  );
});
