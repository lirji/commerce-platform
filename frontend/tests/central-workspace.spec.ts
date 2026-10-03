import { expect, test } from "@playwright/test";
import { centralRoutes, compiledMenus } from "../src/iam/navigation";
import { mkdirSync } from "node:fs";
import { assertButtonSizes, assertCentered } from "./presentation";

// 独立SSO预览只验证壳层与公开DTO，不以夹具证明中央授权或OIDC交换成功。
test("中央页面族统一导航，权限拒绝可见，页签刷新恢复，窄屏不溢出", async ({
  page,
}) => {
  test.setTimeout(180000);
  const base = process.env.COMMERCE_CENTRAL_UI_URL;
  test.skip(!base, "需显式启用独立SSO预览；默认CI不伪装中央登录");
  const tenant = "11111111-1111-4111-8111-111111111111";
  const errors: string[] = [];
  const forbiddenReads: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.addInitScript(
    ({ authority, client }) => {
      sessionStorage.setItem(
        `oidc.user:${authority}:${client}`,
        JSON.stringify({
          access_token: "fixture-central",
          token_type: "Bearer",
          scope: "openid profile",
          profile: { sub: "employee" },
          expires_at: Math.floor(Date.now() / 1000) + 1800,
        }),
      );
    },
    {
      authority:
        process.env.COMMERCE_CENTRAL_AUTHORITY ?? "http://127.0.0.1:18090",
      client: process.env.COMMERCE_CENTRAL_CLIENT ?? "commerce-ui-contract",
    },
  );
  await page.route("**/v1/**", (route) => {
    const path = new URL(route.request().url()).pathname;
    if (["/v1/me", "/v1/stores", "/v1/runtime-capabilities"].includes(path))
      forbiddenReads.push(path);
    // 此项明确是公开DTO界面夹具；新增导航需提供正式本人契约，不回退全菜单。
    const json =
      path === "/v1/operations/navigation"
        ? {
            schemaVersion: "1",
            requestId: tenant,
            context: {
              principalId: tenant,
              membershipId: tenant,
              membershipGeneration: 1,
              membershipVersion: 1,
              principalVersion: 1,
              tenantId: tenant,
              applicationId: "commerce",
              environment: "test",
              actorType: "HUMAN",
              callerServiceId: "ui-fixture",
              traceId: tenant,
            },
            manifestVersion: 1,
            contentHash: "a".repeat(64),
            presentationHash: "b".repeat(64),
            observedAt: new Date().toISOString(),
            state: "AVAILABLE",
            menus: compiledMenus.map(({ any_of: _binding, ...menu }) => menu),
            capabilityHints: ["commerce.product.read"],
          }
        : path.includes("/access") || path.endsWith("-access")
          ? { allowed: false, receive: false, update: false }
          : path === "/v1/operations/scoped/product"
            ? { items: [], stores: 0, total: 0 }
            : path.endsWith("/rule-fields")
              ? {}
              : [];
    return route.fulfill({ json });
  });
  const folder = `${process.env.COMMERCE_EVIDENCE_DIR ?? "../.local/b-console-experience"}/central-screenshots`;
  mkdirSync(folder, { recursive: true });
  for (const path of centralRoutes) {
    await page.goto(`${base}${path}?tenant_id=${tenant}&store_id=store-ui`);
    await expect(page.locator(".central-app")).toBeVisible();
    await expect(page.getByRole("heading", { level: 1 })).toHaveCount(1);
    // 部分旅程页由外层统一处理会话退出；本回归核对各页面实际操作按钮。
    await expect(page.locator(".ant-spin-spinning")).toHaveCount(0);
    await assertButtonSizes(page);
    for (const width of [1440, 390, 320]) {
      await page.setViewportSize({
        width,
        height: width === 1440 ? 1000 : 844,
      });
      await assertButtonSizes(page);
      await page.screenshot({
        path: `${folder}/${path.split("/").slice(1).join("-")}-${width}.png`,
        fullPage: true,
        animations: "disabled",
      });
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth > innerWidth + 1,
        ),
      ).toBe(false);
    }
    await page.setViewportSize({ width: 1440, height: 1000 });
  }
  await page.goto(`${base}/operations/directory?tenant_id=${tenant}`);
  await page.getByRole("tab", { name: "门店目录", exact: true }).click();
  await expect(page).toHaveURL(/workspaceTab=stores/);
  await page.reload();
  await expect(
    page.getByRole("tab", { name: "门店目录", exact: true }),
  ).toHaveAttribute("aria-selected", "true");
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole("button", { name: "打开经营导航", exact: true }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await assertCentered(page);
  await assertButtonSizes(page);
  await page.screenshot({
    path: `${folder}/directory-navigation-390.png`,
    fullPage: true,
    animations: "disabled",
  });
  await page.locator(".ant-modal-close").click();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth > innerWidth + 1,
    ),
  ).toBe(false);
  await page.route("**/v1/admin/stores?**", (route) =>
    route.fulfill({ status: 403, json: {} }),
  );
  await page.reload();
  await expect(
    page.getByText("当前成员没有此操作或资源的权限", { exact: true }),
  ).toBeVisible();
  await page.screenshot({
    path: `${folder}/directory-forbidden-390.png`,
    fullPage: true,
    animations: "disabled",
  });
  expect(errors).toEqual([]);
  expect(forbiddenReads).toEqual([]);
});
