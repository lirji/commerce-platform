import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";
import { chromium, expect } from "@playwright/test";

const root = path.resolve(fileURLToPath(new URL("../../", import.meta.url)));
const CAPTURE_STAGES = {
  BEFORE: "before",
  REPRESENTATIVE: "representative",
  SHARED: "shared",
  AFTER: "after",
};
const stage = process.argv[2] ?? CAPTURE_STAGES.AFTER;
if (!Object.values(CAPTURE_STAGES).includes(stage))
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
async function capture(name, state) {
  await settled();
  const observation = await page.evaluate(() => ({
    route: location.hash,
    viewport: { width: innerWidth, height: innerHeight },
    documentWidth: document.documentElement.scrollWidth,
    rows: document.querySelectorAll(".ant-table-tbody .ant-table-row").length,
    dialogs: [...document.querySelectorAll('[role="dialog"]')]
      .filter((el) => el.checkVisibility())
      .map(
        (el) => el.getAttribute("aria-label") || el.textContent?.slice(0, 80),
      ),
  }));
  const file = name + ".png";
  await page.screenshot({
    animations: "disabled",
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
  if (stage !== CAPTURE_STAGES.BEFORE) {
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
    for (const name of stage === CAPTURE_STAGES.REPRESENTATIVE
      ? ["rules", "members"]
      : routes) {
      await route(name);
      await capture("admin-" + name, "实际管理入口");
      const opener = page
        .locator(".ant-table-tbody")
        .getByRole("button", {
          name: /^(详情|查看详情|查看配置|查看节点|详情与版本|查看|会员详情|任务与受众版本|打开 \/ 版本)$/,
        })
        .first();
      if (await opener.isVisible()) {
        await opener.click();
        await capture("admin-" + name + "-detail", "实际打开的关联详情");
        if (stage === CAPTURE_STAGES.AFTER && name === "orders") {
          await page
            .getByRole("button", { name: "打开完整详情", exact: true })
            .click();
          await expect(page.getByRole("dialog")).toHaveCount(0);
          await capture("admin-order-workspace", "真实订单完整工作区");
          await page
            .getByRole("button", { name: "返回订单列表", exact: true })
            .click();
        } else await close();
      }
      if (
        stage === CAPTURE_STAGES.AFTER &&
        ["journeys", "pages", "segments"].includes(name)
      ) {
        const label = {
          journeys: "创建旅程",
          pages: "创建运营页面",
          segments: "发布人群定义",
        }[name];
        await page.getByRole("button", { name: label, exact: true }).click();
        await capture(
          "admin-" + name + "-editor",
          "实际长编辑器及固定底部操作，未提交",
        );
        await close();
      }
    }
    if (["shared", "after"].includes(stage)) {
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
      await close();
      await page
        .locator(".product-card")
        .first()
        .getByRole("button", { name: "加入购物袋", exact: true })
        .click();
      await page
        .getByRole("button", { name: "购物袋 · 1", exact: true })
        .click();
      await capture("member-cart-mobile", "真实购物袋，未创建订单");
      await page
        .getByRole("button", { name: "计算优惠并结算", exact: true })
        .click();
      await expect(page.getByLabel("收件人", { exact: true })).toBeVisible();
      await page.locator(".ant-drawer-body").evaluate((body) => {
        body.scrollTop = body.scrollHeight;
      });
      await capture("member-checkout-mobile", "实际只读报价与收货表单，未下单");
      await close();
      await route("orders");
      const order = page
        .getByRole("button", { name: "完整详情", exact: true })
        .first();
      if (await order.isVisible()) {
        await order.click();
        await capture(
          "member-order-workspace-mobile",
          "真实会员订单完整详情/手机布局",
        );
        await page
          .getByRole("button", { name: "返回订单列表", exact: true })
          .click();
      }
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
          "Persisted local tenant selected with COMMERCE_VISUAL_ACCESS; credentials excluded",
      },
      null,
      2,
    ) + "\n",
  );
  process.stdout.write(
    JSON.stringify({ stage, screenshots: results.length, errors, folder }),
  );
} catch (error) {
  // 浏览器断言快照可能包含输入中的访问凭据，报告只保留脱敏后的错误摘要。
  let message = String(error?.message ?? "Capture failed");
  for (const [key, value] of Object.entries(access)) {
    if (/token|secret|key/i.test(key) && typeof value === "string")
      message = message.replaceAll(value, "[REDACTED]");
  }
  process.stderr.write(message.slice(0, 1200) + "\n");
  process.exitCode = 1;
} finally {
  await browser.close();
}
