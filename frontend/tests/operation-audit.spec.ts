import { expect, test } from "@playwright/test";
import { randomUUID } from "node:crypto";

const token = process.env.COMMERCE_AUDIT_E2E_TOKEN;
const evidence = process.env.COMMERCE_EVIDENCE_DIR;

test("类目停用可在操作日志查看并回到原类目", async ({ page, request }) => {
  test.skip(!token, "需要隔离数据库签发的类目专岗凭据");
  const code = "OA_UI_" + randomUUID().replaceAll("-", "").toUpperCase();
  const created = await request.post("/v1/platform/commerce-categories", {
    headers: {
      Authorization: `Bearer ${token}`,
      "Idempotency-Key": randomUUID(),
    },
    data: {
      code,
      name: "操作审计浏览器验收 " + code.slice(-8),
      kind: "BRANCH",
      sortOrder: 10,
      reason: "准备验收类目",
    },
  });
  expect(created.ok(), await created.text()).toBeTruthy();
  await page.goto("/#platform-categories?category=" + code);
  await page.getByLabel("访问凭据", { exact: true }).fill(token!);
  await page.getByRole("button", { name: "进入平台", exact: true }).click();

  const category = page.getByRole("row", { name: new RegExp(code) });
  await category.getByRole("button", { name: "维护", exact: true }).click();
  const edit = page.getByRole("dialog", { name: "维护", exact: true });
  await edit.getByRole("switch").click();
  await edit.getByLabel("变更原因").fill("浏览器核对停用证据");
  await edit.getByRole("button", { name: "确认提交", exact: true }).click();
  await expect(
    category.getByRole("cell", { name: "已停用", exact: true }),
  ).toBeVisible();

  await page.getByRole("menuitem", { name: "操作日志", exact: true }).click();
  await page.getByLabel("对象编号").fill(code);
  const [filtered] = await Promise.all([
    page.waitForResponse(
      (response) =>
        new URL(response.url()).searchParams.get("resourceId") === code,
    ),
    page.getByRole("button", { name: "查询", exact: true }).click(),
  ]);
  expect(filtered.ok()).toBeTruthy();
  const audit = page.getByRole("row", { name: new RegExp(code) });
  await expect(audit.getByText("成功", { exact: true })).toBeVisible();
  if (evidence)
    await page.screenshot({ path: evidence + "/oa01-audit-desktop.png" });

  await audit.getByRole("button", { name: "查看详情", exact: true }).click();
  const details = page.getByRole("dialog", {
    name: /^操作日志详情/,
  });
  await expect(details.getByText("浏览器核对停用证据")).toBeVisible();
  await expect(
    details.getByRole("cell", { name: "启停状态", exact: true }),
  ).toBeVisible();
  await expect(
    details.getByRole("cell", { name: "停用", exact: true }),
  ).toBeVisible();
  if (evidence)
    await page.screenshot({ path: evidence + "/oa01-detail-desktop.png" });

  await page.setViewportSize({ width: 390, height: 844 });
  await expect(
    details.getByRole("button", { name: "查看类目", exact: true }),
  ).toBeVisible();
  if (evidence)
    await page.screenshot({ path: evidence + "/oa01-detail-narrow.png" });
  await details.getByRole("button", { name: "查看类目", exact: true }).click();
  await expect(page.getByLabel("按编码或名称查找类目")).toHaveValue(code);
  await expect(
    category.getByRole("cell", { name: "已停用", exact: true }),
  ).toBeVisible();
});
