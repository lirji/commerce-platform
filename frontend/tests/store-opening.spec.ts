import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";
import { accessPath } from "./access";
import { navigate } from "./navigation";

const access = JSON.parse(readFileSync(accessPath("e2e-access.json"), "utf8"));

test("店铺开通草稿可续办，确认后买家目录可见", async ({ page, request }) => {
  const suffix = Date.now().toString(36);
  const storeId = `atelier-${suffix}`;
  const name = `栖木家居 ${suffix}`;
  const buyerStores = async () => {
    const reply = await request.get(`${access.baseUrl}/v1/stores`, {
      headers: { Authorization: `Bearer ${access.memberToken}` },
    });
    expect(reply.ok()).toBeTruthy();
    return (await reply.json()).map(
      (store: { storeId: string }) => store.storeId,
    );
  };
  async function post(path: string, data: unknown, token = access.adminToken) {
    const reply = await request.post(`${access.baseUrl}/v1${path}`, {
      headers: {
        Authorization: `Bearer ${token}`,
        "Idempotency-Key": `${suffix}-${path.split("/").at(-1)}`,
      },
      data,
    });
    expect(reply.ok(), await reply.text()).toBeTruthy();
    return reply.json();
  }

  await page.goto("/");
  await page.getByLabel("访问凭据", { exact: true }).fill(access.adminToken);
  await page.getByRole("button", { name: "进入平台", exact: true }).click();
  await navigate(page, "店铺管理");
  await page.getByRole("button", { name: "新建店铺", exact: true }).click();
  const create = page.getByRole("dialog", { name: "新建店铺", exact: true });
  await create.getByLabel("店铺编号", { exact: true }).fill(storeId);
  await create
    .getByLabel("所属商家编号", { exact: true })
    .fill("browser-merchant");
  await create.getByLabel("店铺名称", { exact: true }).fill(name);
  await create.getByRole("combobox", { name: "经营主体类型" }).click();
  await page
    .locator(".ant-select-item-option-content")
    .filter({ hasText: /^企业$/ })
    .click();
  const category = create.getByRole("combobox", { name: "经营类目" });
  await category.click();
  await category.fill("收纳");
  await page
    .locator(".ant-select-item-option-content")
    .filter({ hasText: /^家居百货 \/ 家居收纳$/ })
    .click();
  await create.getByRole("button", { name: "保存开店草稿" }).click();
  await expect(page.getByText(`开店草稿已保存 · ${storeId}`)).toBeVisible();
  expect(await buyerStores()).not.toContain(storeId);

  await page.reload();
  const row = page.getByRole("row", { name: new RegExp(storeId) });
  await expect(
    row.getByRole("cell", { name: "待开店", exact: true }),
  ).toBeVisible();
  await row.getByRole("button", { name: "继续开店" }).click();
  const profile = page.getByRole("dialog", { name: "开店资料", exact: true });
  await profile.getByRole("button", { name: "修改开店资料" }).click();
  const revise = page.getByRole("dialog", {
    name: "修改开店资料",
    exact: true,
  });
  const newCategory = revise.getByRole("combobox", { name: "经营类目" });
  await newCategory.click();
  await newCategory.fill("餐具");
  await page
    .locator(".ant-select-item-option-content")
    .filter({ hasText: /^家居百货 \/ 厨具餐具$/ })
    .click();
  await revise.getByRole("button", { name: "确认提交", exact: true }).click();
  await expect(
    profile.getByText("家居百货 / 厨具餐具", { exact: true }),
  ).toBeVisible();
  await profile.getByRole("button", { name: "确认开店", exact: true }).click();
  const open = page.getByRole("dialog", { name: "确认开店", exact: true });
  await open
    .getByLabel("审核记录编号", { exact: true })
    .fill(`REVIEW-${suffix}`);
  await open.getByRole("button", { name: "确认开店", exact: true }).click();
  await expect(
    row.getByRole("cell", { name: "可用", exact: true }),
  ).toBeVisible();
  expect(await buyerStores()).toContain(storeId);
  await row.getByRole("button", { name: "开店资料", exact: true }).click();
  await expect(
    profile.getByText(`REVIEW-${suffix}`, { exact: true }),
  ).toBeVisible();

  const skuId = `organizer-${suffix}`;
  await post("/admin/skus", {
    storeId,
    skuId,
    title: "木质收纳盒",
    unitPrice: "59.00",
  });
  await post("/admin/inventory/receipts", { storeId, skuId, quantity: 5 });
  const quote = await post(
    "/quotes",
    { storeId, items: [{ skuId, quantity: 2 }] },
    access.memberToken,
  );
  expect(quote).toMatchObject({ storeId, payable: "118.00" });
});
