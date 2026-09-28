import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";
import { chromium, expect } from "@playwright/test";

const root = path.resolve(fileURLToPath(new URL("../../", import.meta.url)));
const stage = process.argv[2] ?? "after";
if (!["before", "representative", "after"].includes(stage))
  throw new Error("Invalid capture stage");
const folder = path.join(
  root,
  "docs/evidence/frontend-interaction-refresh",
  stage,
);
fs.mkdirSync(folder, { recursive: true });
const access = JSON.parse(
  fs.readFileSync(
    process.env.COMMERCE_VISUAL_ACCESS ??
      path.join(root, ".local/member-suite-access.json"),
    "utf8",
  ),
);
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
const results = [],
  errors = [];
page.on("pageerror", (error) => errors.push(error.message));
async function settled() {
  await page.waitForLoadState("networkidle");
  await page.locator(".page-loading").waitFor({ state: "hidden" });
  await page.locator(".ant-spin-spinning").first().waitFor({ state: "hidden" });
  await page.evaluate(() => document.fonts.ready);
  await page.evaluate(
    () =>
      new Promise((resolve) =>
        requestAnimationFrame(() => requestAnimationFrame(resolve)),
      ),
  );
}
async function capture(name, state) {
  await settled();
  const observation = await page.evaluate(() => ({
    route: location.hash,
    viewport: { width: innerWidth, height: innerHeight },
    documentWidth: document.documentElement.scrollWidth,
    rows: document.querySelectorAll(".ant-table-tbody .ant-table-row").length,
    dialogs: [...document.querySelectorAll('[role="dialog"]')].map(
      (el) => el.getAttribute("aria-label") || el.textContent?.slice(0, 80),
    ),
  }));
  const file = name + ".png";
  await page.screenshot({
    path: path.join(folder, file),
    fullPage: !observation.dialogs.length,
  });
  results.push({
    file,
    state,
    timestamp: new Date().toISOString(),
    ...observation,
  });
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
async function close() {
  await page
    .getByRole("dialog")
    .last()
    .getByRole("button", { name: "关闭", exact: true })
    .click();
  if (
    await page
      .getByRole("button", { name: "放弃修改", exact: true })
      .isVisible()
  )
    await page.getByRole("button", { name: "放弃修改", exact: true }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
}
try {
  await login(access.adminToken);
  await route("campaigns");
  await capture("campaign-list", "营销列表与行操作/分页");
  await page
    .getByRole("button", { name: "查看配置", exact: true })
    .first()
    .click();
  await capture("campaign-detail", "实际活动配置");
  await close();
  await page
    .getByRole("button", { name: "复制新版本", exact: true })
    .first()
    .click();
  await capture("campaign-editor", "基于真实活动的一次编辑，未提交");
  await close();
  await page
    .getByRole("button", { name: "预览优惠", exact: true })
    .first()
    .click();
  await capture("campaign-preview", "真实预览入口与表单，未执行业务写");
  await close();
  if (stage !== "before") {
    const routes = [
      "dashboard",
      "members",
      "growth",
      "member-tags",
      "segments",
      "audiences",
      "skus",
      "inventory",
      "merchants",
      "stores",
      "store-grants",
      "effects",
      "rules",
      "budgets",
      "coupons",
      "coupon-deliveries",
      "definitions",
      "entitlements",
      "journeys",
      "instances",
      "orders",
      "fulfillments",
      "aftersales",
      "refunds",
      "pages",
      "events",
    ];
    for (const name of stage === "representative"
      ? ["rules", "members"]
      : routes) {
      await route(name);
      await capture("admin-" + name, "实际管理入口");
      const opener = page
        .locator(".ant-table-tbody")
        .getByRole("button", {
          name: /^(详情|查看详情|查看配置|查看节点|详情与版本|查看)$/,
        })
        .first();
      if (await opener.isVisible()) {
        await opener.click();
        await capture("admin-" + name + "-detail", "实际打开的关联详情");
        await close();
      }
    }
    if (stage === "after") {
      await page.getByRole("button", { name: "退出", exact: true }).click();
      await login(access.memberToken);
      for (const name of [
        "shop",
        "orders",
        "coupons",
        "benefits",
        "growth",
        "aftersales",
        "notifications",
      ]) {
        await route(name);
        await capture("member-" + name, "实际会员入口");
      }
      await page.setViewportSize({ width: 390, height: 844 });
      await route("shop");
      await capture("member-shop-mobile", "既有会员窄屏");
      await page.locator(".product-title").first().click();
      await capture("member-product-mobile", "真实商品详情");
    }
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
        fixture:
          "Existing isolated persisted member-suite tenant; credentials excluded",
      },
      null,
      2,
    ) + "\n",
  );
  console.log(
    JSON.stringify({ stage, screenshots: results.length, errors, folder }),
  );
} catch (error) {
  // 浏览器断言快照可能包含输入中的访问凭据，报告只保留脱敏后的错误摘要。
  let message = String(error?.message ?? "Capture failed");
  for (const [key, value] of Object.entries(access)) {
    if (/token|secret|key/i.test(key) && typeof value === "string")
      message = message.replaceAll(value, "[REDACTED]");
  }
  console.error(message.slice(0, 1200));
  process.exitCode = 1;
} finally {
  await browser.close();
}
