import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";
import { chromium, expect } from "@playwright/test";

const root = fileURLToPath(new URL("../../", import.meta.url));
const access = JSON.parse(
  fs.readFileSync(
    process.env.COMMERCE_VISUAL_ACCESS ??
      path.join(root, ".local/member-suite-access.json"),
    "utf8",
  ),
);
const campaignAccess = process.env.COMMERCE_CAMPAIGN_ACCESS
  ? JSON.parse(fs.readFileSync(process.env.COMMERCE_CAMPAIGN_ACCESS, "utf8"))
  : undefined;
const operatorAccess = process.env.COMMERCE_OPERATOR_ACCESS
  ? JSON.parse(fs.readFileSync(process.env.COMMERCE_OPERATOR_ACCESS, "utf8"))
  : access;
const folder = path.join(
  root,
  "docs/evidence/frontend-interaction-refresh/associated",
);
fs.mkdirSync(folder, { recursive: true });
const hashes = {};
function hashSources(dir) {
  for (const name of fs.readdirSync(dir)) {
    const file = path.join(dir, name);
    if (fs.statSync(file).isDirectory()) hashSources(file);
    else
      hashes[path.relative(root, file)] = crypto
        .createHash("sha256")
        .update(fs.readFileSync(file))
        .digest("hex");
  }
}
hashSources(path.join(root, "frontend/src"));
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const RUNNING_ANIMATION = "running";
const results = [],
  errors = [];
page.on("pageerror", (error) => errors.push(error.message));
async function settled() {
  await page.waitForLoadState("networkidle");
  await page.locator(".page-loading").waitFor({ state: "hidden" });
  await page.locator(".ant-spin-spinning").first().waitFor({ state: "hidden" });
  await page.evaluate(async (runningState) => {
    await document.fonts.ready;
    const transitions = document.getAnimations().filter((animation) => {
      const end = animation.effect?.getComputedTiming().endTime;
      return animation.playState === runningState && Number.isFinite(end);
    });
    await Promise.allSettled(
      transitions.map((animation) => animation.finished),
    );
  }, RUNNING_ANIMATION);
  await page.evaluate(
    () =>
      new Promise((resolve) =>
        requestAnimationFrame(() => requestAnimationFrame(resolve)),
      ),
  );
}
async function capture(name, state, fullPage) {
  await settled();
  const modal = page.locator(".ant-modal:visible").last();
  if (await modal.count()) await modal.screenshot({ animations: "disabled" });
  const observation = await page.evaluate(() => ({
    route: location.hash,
    viewport: { width: innerWidth, height: innerHeight },
    documentWidth: document.documentElement.scrollWidth,
    dialogs: [...document.querySelectorAll('[role="dialog"]')].filter((el) =>
      el.checkVisibility(),
    ).length,
  }));
  const file = name + ".png";
  await page.screenshot({
    animations: "disabled",
    path: path.join(folder, file),
    fullPage: fullPage ?? !observation.dialogs,
  });
  results.push({ file, state, ...observation });
}
async function login(token) {
  await page.goto(process.env.COMMERCE_UI_URL ?? "http://127.0.0.1:8601");
  await page.getByLabel("访问凭据", { exact: true }).fill(token);
  await page.getByRole("button", { name: "进入平台", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "退出", exact: true }),
  ).toBeVisible();
  await settled();
}
async function route(name) {
  const store = await page.evaluate(() =>
    new URLSearchParams(location.hash.split("?")[1]).get("store"),
  );
  await page.evaluate(
    ({ name, store }) => {
      location.hash =
        name + (store ? "?store=" + encodeURIComponent(store) : "");
    },
    { name, store },
  );
  await settled();
}
async function closeOne() {
  const prior = await page.getByRole("dialog").count();
  await page
    .getByRole("dialog")
    .last()
    .getByRole("button", { name: "关闭", exact: true })
    .click();
  const discard = page.getByRole("button", { name: "放弃修改", exact: true });
  if (await discard.isVisible()) await discard.click();
  await expect(page.getByRole("dialog")).toHaveCount(prior - 1);
}
try {
  await login(access.adminToken);
  await route("skus");
  await page
    .getByRole("button", { name: "渠道价", exact: true })
    .first()
    .click();
  await capture("channel-prices", "渠道窗口与历史入口");
  await page.getByRole("button", { name: "设置网站价", exact: true }).click();
  await capture("channel-price-editor", "真实渠道价嵌套短表单，未提交");
  await closeOne();
  await closeOne();
  await page
    .getByRole("button", { name: "条码资料", exact: true })
    .first()
    .click();
  await capture("barcode-detail", "经营条码资料");
  await closeOne();
  await page.getByRole("button", { name: "创建销售规格", exact: true }).click();
  await capture("variant-editor", "规格编辑器的固定操作，未提交");
  await closeOne();
  await page.getByRole("tab", { name: "商品资料（SPU）", exact: true }).click();
  await settled();
  await page
    .getByRole("button", { name: "展示资料", exact: true })
    .first()
    .click();
  await capture("product-presentation", "真实图文资料");
  await page.getByRole("button", { name: "编辑展示资料", exact: true }).click();
  await capture("product-presentation-editor", "图片说明与资料短表单，未提交");
  await closeOne();
  await closeOne();
  for (const label of ["批量定时计划", "类目与模板"]) {
    await page.getByRole("tab", { name: label, exact: true }).click();
    await capture(
      label === "类目与模板" ? "catalog-structure" : "catalog-jobs",
      "商品经营关联页",
    );
  }
  await route("growth");
  for (const [label, query] of [
    ["成长与账本", "查询会员成长"],
    ["会员详情与行为", "查询会员行为"],
    ["积分账户", "查询会员积分"],
    ["周期与等级权益", "查询会员周期"],
    ["积分兑换", null],
    ["成长与等级规则", null],
  ]) {
    await page.getByRole("tab", { name: label, exact: true }).click();
    if (query) {
      const input = page.getByLabel(query, { exact: true });
      await input.fill("suite-member");
      await input.press("Enter");
    }
    await capture(
      "growth-" +
        [
          "成长与账本",
          "会员详情与行为",
          "积分账户",
          "周期与等级权益",
          "积分兑换",
          "成长与等级规则",
        ].indexOf(label),
      "真实会员子模块：" + label,
    );
  }
  await route("effects");
  for (const label of [
    "活动成交与退款",
    "会员营销比较",
    "旅程执行",
    "补齐历史订单",
  ]) {
    await page.getByRole("tab", { name: label, exact: true }).click();
    await capture(
      "effects-" +
        ["活动成交与退款", "会员营销比较", "旅程执行", "补齐历史订单"].indexOf(
          label,
        ),
      "实际报表关联页：" + label,
    );
  }
  await page.getByRole("button", { name: "退出", exact: true }).click();
  await login(operatorAccess.operatorToken);
  await capture("operator-catalog", "真实门店运营角色");
  await page.setViewportSize({ width: 390, height: 844 });
  await page
    .getByRole("button", { name: "调价 / 上下架", exact: true })
    .first()
    .click();
  await capture("operator-editor-mobile", "运营窄屏短表单，未提交");
  await closeOne();
  if (campaignAccess) {
    await page.getByRole("button", { name: "退出", exact: true }).click();
    await page.setViewportSize({ width: 1440, height: 1000 });
    await login(campaignAccess.adminToken);
    await route("campaigns");
    await page
      .getByRole("button", { name: "预览优惠", exact: true })
      .first()
      .click();
    await page.getByLabel("会员标识", { exact: true }).fill("member-demo");
    await page.getByLabel("购物清单", { exact: true }).fill("sku-coffee,1");
    await page.getByRole("button", { name: "计算预览", exact: true }).click();
    await expect(page.getByText("预计应付", { exact: true })).toBeVisible();
    await capture("campaign-preview-result", "真实API只读试算结果");
    await closeOne();
    await page.getByRole("button", { name: "退出", exact: true }).click();
    await login(campaignAccess.memberToken);
    await route("orders");
    await page.setViewportSize({ width: 390, height: 844 });
    await page
      .getByRole("button", { name: "完整详情", exact: true })
      .first()
      .click();
    await page
      .locator(".order-workspace")
      .getByRole("columnheader", { name: "商品", exact: true })
      .scrollIntoViewIfNeeded();
    await capture(
      "member-order-content-mobile",
      "真实订单商品明细与支付操作",
      false,
    );
  }
  for (const [file, hash] of Object.entries(hashes))
    if (
      crypto
        .createHash("sha256")
        .update(fs.readFileSync(path.join(root, file)))
        .digest("hex") !== hash
    )
      throw new Error("Source changed during capture");
  fs.writeFileSync(
    path.join(folder, "capture.json"),
    JSON.stringify(
      {
        hashes,
        results,
        errors,
        fixture: "Persisted isolated test tenants; no credentials",
      },
      null,
      2,
    ) + "\n",
  );
  process.stdout.write(
    JSON.stringify({ screenshots: results.length, errors, folder }),
  );
} catch (error) {
  let message = String(error?.message ?? "Capture failed");
  for (const credentials of [access, campaignAccess, operatorAccess])
    for (const [key, value] of Object.entries(credentials ?? {}))
      if (/token|secret|key/i.test(key) && typeof value === "string")
        message = message.replaceAll(value, "[REDACTED]");
  process.stderr.write(message.slice(0, 1200) + "\n");
  process.exitCode = 1;
} finally {
  await browser.close();
}
