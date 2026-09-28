import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";
import { chromium } from "@playwright/test";

const root = path.resolve(fileURLToPath(new URL("../../", import.meta.url)));
const preview = process.env.COMMERCE_UI_URL || "http://127.0.0.1:8601";
const CAPTURE_STAGES = {
  BEFORE: "before",
  REPRESENTATIVE: "representative",
  AFTER: "after",
};
const stage = process.argv[2] || CAPTURE_STAGES.AFTER;
if (!Object.values(CAPTURE_STAGES).includes(stage))
  throw new Error("Expected before, representative or after stage");
const folder = path.join(
  process.env.COMMERCE_VISUAL_EVIDENCE_DIR ||
    path.join(root, "docs/evidence/frontend-visual-refresh"),
  stage,
);
fs.mkdirSync(folder, { recursive: true });
const access = JSON.parse(
  fs.readFileSync(
    process.env.COMMERCE_VISUAL_ACCESS ||
      path.join(root, ".local/member-suite-access.json"),
    "utf8",
  ),
);
const sources = [
  "frontend/src/shared/Icon.tsx",
  "frontend/src/features/ProductOperations.tsx",
  "frontend/src/theme.ts",
  "frontend/src/style.css",
  "frontend/src/app/App.tsx",
  "frontend/src/shared/ui.tsx",
  "frontend/src/features/Dashboard.tsx",
  "frontend/src/features/Shop.tsx",
];
const hashes = Object.fromEntries(
  sources.map((file) => [
    file,
    crypto
      .createHash("sha256")
      .update(fs.readFileSync(path.join(root, file)))
      .digest("hex"),
  ]),
);
const browser = await chromium.launch({ headless: true });
const errors = [];
const results = [];
const context = await browser.newContext({
  viewport: { width: 1440, height: 1000 },
});
const page = await context.newPage();
page.on("pageerror", (error) => errors.push(error.message));
async function screenshot(name, state = "normal", loading = false) {
  await page.evaluate(() => document.fonts.ready);
  if (!loading)
    await page
      .locator(".ant-spin-spinning")
      .waitFor({ state: "hidden", timeout: 20000 });
  await page.waitForFunction(
    () => document.documentElement.scrollWidth <= innerWidth + 1,
  );
  await page.evaluate(() => scrollTo(0, 0));
  await page.evaluate(
    () =>
      new Promise((resolve) =>
        requestAnimationFrame(() => requestAnimationFrame(resolve)),
      ),
  );
  const observations = await page.evaluate(() => ({
    route: location.hash,
    viewport: { width: innerWidth, height: innerHeight },
    documentWidth: document.documentElement.scrollWidth,
    images: [...document.images].map((image) => ({
      src: new URL(image.src).pathname,
      loaded: image.complete && image.naturalWidth > 0,
      alt: image.alt,
    })),
    headings: [...document.querySelectorAll("h1,h2")].map(
      (heading) => heading.textContent,
    ),
  }));
  const file = `${name}.png`;
  const overlay = (await page.getByRole("dialog").count()) > 0;
  await page.screenshot({ path: path.join(folder, file), fullPage: !overlay });
  results.push({
    file,
    state,
    screenshotMode: overlay ? "viewport" : "fullPage",
    timestamp: new Date().toISOString(),
    ...observations,
  });
}
async function login(token) {
  await page.goto(preview);
  await page.getByLabel("访问凭据", { exact: true }).fill(token);
  await page.getByRole("button", { name: "进入平台", exact: true }).click();
  await page.getByRole("button", { name: "退出", exact: true }).waitFor();
}
async function navigate(label) {
  const search = page.getByRole("combobox", { name: "搜索功能", exact: true });
  await search.fill(label);
  await page
    .locator(".ant-select-item-option-content")
    .filter({ hasText: new RegExp("^" + label + "$") })
    .click();
}
try {
  await page.goto(preview);
  await screenshot("login-desktop");
  await login(access.adminToken);
  await page.locator(".metric-card").first().waitFor();
  await screenshot("dashboard-desktop");
  await page.setViewportSize({ width: 1280, height: 900 });
  await screenshot("dashboard-1280");
  await page.setViewportSize({ width: 390, height: 844 });
  await screenshot("dashboard-mobile");
  await page.setViewportSize({ width: 1440, height: 1000 });
  await navigate("商品管理");
  await page.getByRole("heading", { name: "商品经营", exact: true }).waitFor();
  await page.getByText("积分精品咖啡", { exact: true }).first().waitFor();
  await screenshot("catalog-desktop");
  await page.getByRole("tab", { name: "商品资料（SPU）", exact: true }).click();
  await page.getByRole("button", { name: "创建商品", exact: true }).click();
  await page.getByRole("button", { name: "确认提交", exact: true }).click();
  await page.getByText("请输入商品标识", { exact: true }).waitFor();
  await screenshot("catalog-form-errors");
  await page.getByRole("button", { name: "关闭", exact: true }).click();
  await navigate("订单工作台");
  await page
    .getByRole("heading", { name: "订单工作台", exact: true })
    .waitFor();
  await screenshot("orders-desktop");
  await page.getByRole("button", { name: "退出", exact: true }).click();
  await login(access.memberToken);
  await page.locator(".product-card").first().waitFor();
  await screenshot("shop-desktop");
  await page.setViewportSize({ width: 390, height: 844 });
  await screenshot("shop-mobile");
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.locator(".product-title").first().click();
  await page.locator(".product-detail").waitFor();
  await screenshot("product-detail");
  if (stage === CAPTURE_STAGES.AFTER) {
    await page.getByRole("button", { name: "返回店铺", exact: true }).click();
    await page.setViewportSize({ width: 390, height: 844 });
    await page.locator(".product-title").first().click();
    await page.locator(".product-detail").waitFor();
    await screenshot("product-detail-mobile", "真实商品详情");
    await page
      .getByRole("button", { name: "返回店铺", exact: true })
      .scrollIntoViewIfNeeded();
    await screenshot(
      "product-detail-mobile-actions",
      "固定弹层滚动后可达返回动作",
    );
    await page.getByRole("button", { name: "返回店铺", exact: true }).click();
    await page
      .locator(".product-card")
      .first()
      .getByRole("button", { name: "加入购物袋", exact: true })
      .click();
    await page.getByRole("button", { name: "购物袋 · 1", exact: true }).click();
    await page
      .getByRole("button", { name: "计算优惠并结算", exact: true })
      .click();
    await page.getByRole("button", { name: /^确认下单 ·/ }).waitFor();
    await screenshot("checkout-mobile", "真实API报价，未提交订单");
    await page.locator(".checkout-quote").scrollIntoViewIfNeeded();
    await screenshot(
      "checkout-quote-mobile",
      "抽屉内部滚动，真实报价与收货表单",
    );
    await page.getByRole("button", { name: /^确认下单 ·/ }).click();
    await page.locator(".ant-form-item-explain-error").first().waitFor();
    await screenshot("checkout-errors-mobile", "收货表单必填校验");
    await page.getByRole("button", { name: "关闭", exact: true }).click();
    await page
      .getByLabel("商品名称或条码", { exact: true })
      .fill("visual-no-result-" + crypto.randomUUID());
    await page.getByRole("button", { name: "搜索", exact: true }).click();
    await page.getByText("没有符合筛选条件的商品", { exact: true }).waitFor();
    await screenshot("shop-empty-mobile", "真实API无匹配搜索");
    await page.getByRole("button", { name: "退出", exact: true }).click();
    await login(access.adminToken);
    await page.locator(".metric-card").first().waitFor();
    await navigate("商品管理");
    await page.locator(".status-tag").first().waitFor();
    await screenshot("catalog-mobile", "宽表在容器内滚动");
    await page
      .getByRole("tab", { name: "商品资料（SPU）", exact: true })
      .click();
    await page.getByRole("button", { name: "创建商品", exact: true }).click();
    await page.getByRole("button", { name: "确认提交", exact: true }).click();
    await page.getByText("请输入商品标识", { exact: true }).waitFor();
    await page.getByLabel("商品标识", { exact: true }).focus();
    await page.keyboard.press("Tab");
    await screenshot("catalog-form-mobile", "必填错误及键盘焦点");
    await page.getByRole("button", { name: "关闭", exact: true }).click();
    await navigate("经营总览");
    await page.locator(".metric-card").first().waitFor();
    await page
      .getByRole("button", { name: "查看每日数据", exact: true })
      .click();
    await screenshot("dashboard-data-mobile", "每日数据表展开");
    await page
      .getByRole("button", { name: "收起每日数据", exact: true })
      .click();
    await page.setViewportSize({ width: 1440, height: 1000 });
    let release;
    const pending = new Promise((resolve) => {
      release = resolve;
    });
    await page.route("**/v1/admin/dashboard?*", async (route) => {
      await pending;
      await route.continue();
    });
    await page.getByRole("button", { name: "刷新总览", exact: true }).click();
    await page.locator(".ant-spin-spinning").waitFor();
    await screenshot("dashboard-loading", "真实API请求暂缓的加载边界", true);
    release();
    await page.locator(".ant-spin-spinning").waitFor({ state: "hidden" });
    await page.unroute("**/v1/admin/dashboard?*");
    await page.route("**/v1/admin/dashboard?*", (route) =>
      route.fulfill({
        status: 503,
        contentType: "application/json",
        body: JSON.stringify({ message: "总览暂时不可用" }),
      }),
    );
    await page.getByRole("button", { name: "刷新总览", exact: true }).click();
    await page.getByText("总览暂时不可用", { exact: true }).waitFor();
    await screenshot("dashboard-error", "声明的503测试边界，不是后端真实故障");
  }
  for (const [file, hash] of Object.entries(hashes)) {
    if (
      crypto
        .createHash("sha256")
        .update(fs.readFileSync(path.join(root, file)))
        .digest("hex") !== hash
    )
      throw new Error("Source changed during capture: " + file);
  }
  fs.writeFileSync(
    path.join(folder, "capture.json"),
    JSON.stringify(
      {
        stage,
        hashes,
        results,
        errors,
        preview,
        api: access.baseUrl,
        fixture:
          "Dedicated persisted member-suite tenant; credentials excluded",
      },
      null,
      2,
    ) + "\n",
  );
  process.stdout.write(
    JSON.stringify({ stage, screenshots: results.length, errors, folder }) +
      "\n",
  );
} finally {
  await browser.close();
}
