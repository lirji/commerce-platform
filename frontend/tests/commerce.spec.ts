import { navigate } from "./navigation";
import { test, expect, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
const access = JSON.parse(
  readFileSync(
    new URL("../../.local/e2e-access.json", import.meta.url),
    "utf8",
  ),
);
const evidence = process.env.COMMERCE_EVIDENCE_DIR ?? "../docs/evidence/s10a";
async function login(page: Page, role: "admin" | "member") {
  await page.goto("/");
  await page
    .getByLabel("访问凭据", { exact: true })
    .fill(access[role + "Token"]);
  await page.getByRole("button", { name: "进入平台", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "退出", exact: true }),
  ).toBeVisible();
}
async function api(path: string, role: "admin" | "member" = "admin") {
  const r = await fetch(access.baseUrl + "/v1" + path, {
    headers: { Authorization: "Bearer " + access[role + "Token"] },
  });
  expect(r.status).toBe(200);
  return r.json();
}
async function nav(page: Page, label: string) {
  await navigate(page, label);
}
async function submit(page: Page, title: string) {
  await page.getByRole("button", { name: title, exact: true }).click();
  await page.getByRole("button", { name: "确认提交", exact: true }).click();
}
test("真实浏览器：会员下单、沙箱收款、履约、售后退款与权益冲正", async ({
  browser,
}) => {
  const member = await browser.newPage();
  const admin = await browser.newPage();
  const errors: string[] = [];
  member.on("pageerror", (e) => errors.push(e.message));
  admin.on("pageerror", (e) => errors.push(e.message));
  await login(member, "member");
  await expect(member.getByText("精品咖啡礼盒", { exact: true })).toBeVisible();
  await member.screenshot({
    path: `${evidence}/member-shop.png`,
    fullPage: true,
  });
  await nav(member, "优惠券");
  await member.getByRole("button", { name: "领取优惠券", exact: true }).click();
  await expect
    .poll(async () => (await api("/coupons", "member")).length)
    .toBe(1);
  await nav(member, "逛店铺");
  const card = member
    .locator(".product-card")
    .filter({ hasText: "精品咖啡礼盒" });
  await card.getByRole("button", { name: "加入购物袋" }).click();
  await member.getByRole("button", { name: "购物袋 · 1" }).click();
  await member.getByLabel("选择优惠券").click();
  await member.getByText(/新会员加享券 · 减/).click();
  await member.getByRole("button", { name: "计算优惠并结算" }).click();
  await expect(
    member.getByRole("button", { name: "确认下单 · ¥104.00" }),
  ).toBeVisible();
  await member.getByLabel("收件人", { exact: true }).fill("验收会员");
  await member.getByLabel("联系电话", { exact: true }).fill("13800000000");
  await member
    .getByLabel("收货地址", { exact: true })
    .fill("隔离验收地址，不用于真实发货");
  await member.getByRole("button", { name: "确认下单 · ¥104.00" }).click();
  await member
    .getByRole("button", { name: "查看详情", exact: true })
    .first()
    .click();
  await member.getByRole("button", { name: "发起支付", exact: true }).click();
  await expect(member.getByText("结果待确认", { exact: true })).toBeVisible();
  const order = (await api("/orders", "member"))[0];
  await login(admin, "admin");
  await nav(admin, "订单工作台");
  await admin
    .getByRole("button", { name: "查看详情", exact: true })
    .first()
    .click();
  await admin.getByRole("button", { name: "查询支付", exact: true }).click();
  await admin
    .getByRole("button", { name: "沙箱：模拟收款", exact: true })
    .click();
  await admin
    .getByRole("button", { name: "核对渠道结果", exact: true })
    .click();
  await expect
    .poll(async () => (await api("/admin/orders/" + order.orderId)).status, {
      timeout: 15000,
    })
    .toBe("PAID");
  await admin.getByRole("button", { name: "关闭", exact: true }).click();
  await expect
    .poll(
      async () =>
        (await api("/admin/fulfillments")).some(
          (r: { orderId: string }) => r.orderId === order.orderId,
        ),
      { timeout: 15000 },
    )
    .toBe(true);
  await nav(admin, "履约队列");
  await expect(admin.getByRole("button", { name: "沙箱发货" })).toBeVisible();
  await admin.getByRole("button", { name: "沙箱发货" }).click();
  await admin.getByLabel("物流单号").fill("BROWSER-TRACK-001");
  await admin.getByRole("button", { name: "确认提交" }).click();
  await expect(admin.getByRole("button", { name: "沙箱签收" })).toBeVisible();
  await admin.getByRole("button", { name: "沙箱签收" }).click();
  await expect
    .poll(async () => (await api("/admin/orders/" + order.orderId)).status)
    .toBe("COMPLETED");
  await member.getByRole("button", { name: "刷新状态", exact: true }).click();
  await member.getByRole("button", { name: "申请售后", exact: true }).click();
  await member
    .getByRole("spinbutton", { name: "精品咖啡礼盒退货数量" })
    .fill("1");
  await member.getByRole("button", { name: "提交售后申请" }).click();
  await member.getByLabel("申请原因").fill("全链路验收退货");
  await member.getByRole("button", { name: "确认提交" }).click();
  await nav(admin, "售后审批");
  await admin.getByRole("button", { name: "批准", exact: true }).click();
  await admin
    .getByRole("button", { name: "确认退货入库", exact: true })
    .click();
  await nav(admin, "退款核对");
  await admin
    .getByRole("button", { name: "沙箱：模拟退款成功", exact: true })
    .click();
  await admin.getByRole("button", { name: "核对退款", exact: true }).click();
  await expect
    .poll(async () => (await api("/admin/aftersales"))[0].status, {
      timeout: 15000,
    })
    .toBe("COMPLETED");
  await expect
    .poll(async () => (await api("/entitlements", "member"))[0].status)
    .toBe("REVOKED");
  await nav(admin, "订单工作台");
  await admin.screenshot({
    path: `${evidence}/admin-orders.png`,
    fullPage: true,
  });
  expect(errors).toEqual([]);
  await admin.close();
  await member.close();
});
test("可视规则与低代码页面预览、审批、发布及窄屏", async ({
  page,
  browser,
}) => {
  await login(page, "admin");
  await nav(page, "动态规则");
  await page.getByRole("button", { name: "新建规则", exact: true }).click();
  await page.getByLabel("规则标识", { exact: true }).fill("browser-rule");
  await page.getByLabel("规则名称", { exact: true }).fill("浏览器创建VIP规则");
  await page.getByLabel("比较值", { exact: true }).fill("VIP");
  await page.getByRole("button", { name: "保存规则", exact: true }).click();
  await page.getByRole("button", { name: "发布规则", exact: true }).click();
  await expect(page.getByText("已发布", { exact: true })).toBeVisible();
  await nav(page, "低代码页面");
  await page.getByRole("button", { name: "创建运营页面", exact: true }).click();
  await page.getByLabel("页面标识", { exact: true }).fill("browser-page");
  await page.getByLabel("页面标题", { exact: true }).fill("会员营销运营台");
  await page.getByRole("button", { name: "预览真实数据", exact: true }).click();
  await expect(
    page
      .locator(".readonly-preview")
      .getByText("会员满100减20", { exact: true }),
  ).toBeVisible();
  await page.screenshot({
    path: `${evidence}/lowcode-preview.png`,
    fullPage: true,
  });
  await page.getByRole("button", { name: "保存页面草稿", exact: true }).click();
  for (const action of ["提交审批", "批准", "发布"])
    await page.getByRole("button", { name: action, exact: true }).click();
  await page.getByRole("button", { name: "打开 / 版本", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "会员营销运营台", exact: true }),
  ).toBeVisible();
  const mobile = await browser.newPage({
    viewport: { width: 390, height: 844 },
  });
  await login(mobile, "member");
  await expect(mobile.getByText("精品咖啡礼盒", { exact: true })).toBeVisible();
  await mobile.screenshot({
    path: `${evidence}/member-mobile.png`,
    fullPage: true,
  });
  expect(
    await mobile.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await mobile.close();
});
test("无效凭据不会进入应用，退出后清除会话", async ({ page }) => {
  await page.goto("/");
  await page.getByLabel("访问凭据", { exact: true }).fill("invalid-token");
  await page.getByRole("button", { name: "进入平台", exact: true }).click();
  await expect(page.getByRole("alert")).toBeVisible();
  await login(page, "member");
  await page.getByRole("button", { name: "退出", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "进入平台", exact: true }),
  ).toBeVisible();
  expect(await page.evaluate(() => Object.keys(localStorage).length)).toBe(0);
});
test("旅程可视编排、手工入组与会员站内触达", async ({ page, browser }) => {
  await login(page, "admin");
  await nav(page, "营销旅程");
  await page.getByRole("button", { name: "创建旅程", exact: true }).click();
  await page.getByLabel("旅程标识", { exact: true }).fill("browser-journey");
  await page.getByLabel("旅程名称", { exact: true }).fill("浏览器关怀旅程");
  await page.getByLabel("节点1标识", { exact: true }).fill("notice");
  await page.getByLabel("节点1类型", { exact: true }).click();
  await page.getByText("站内触达", { exact: true }).click();
  await page.getByLabel("消息标题", { exact: true }).fill("欢迎参加会员旅程");
  await page
    .getByLabel("消息内容", { exact: true })
    .fill("这条消息由持久旅程节点生成。");
  await page.getByRole("button", { name: "添加节点", exact: true }).click();
  await page.getByLabel("节点1下一节点", { exact: true }).click();
  await page
    .locator(".ant-select-item-option-content")
    .filter({ hasText: /^node-2$/ })
    .click();
  await page.getByRole("button", { name: "保存旅程草稿", exact: true }).click();
  for (const action of ["提交审批", "批准", "发布"])
    await page.getByRole("button", { name: action, exact: true }).click();
  await nav(page, "旅程实例");
  await page.getByRole("button", { name: "手工入组", exact: true }).click();
  await page.getByLabel("旅程标识", { exact: true }).fill("browser-journey");
  await page.getByLabel("会员标识", { exact: true }).fill("browser-member");
  await page
    .getByLabel("触发去重标识", { exact: true })
    .fill("browser-welcome");
  await page.getByRole("button", { name: "确认提交", exact: true }).click();
  await expect
    .poll(
      async () =>
        (await api("/notifications", "member")).some(
          (n: { title: string }) => n.title === "欢迎参加会员旅程",
        ),
      { timeout: 15000 },
    )
    .toBe(true);
  const member = await browser.newPage();
  await login(member, "member");
  await nav(member, "消息");
  await expect(
    member.getByText("欢迎参加会员旅程", { exact: true }),
  ).toBeVisible();
  await member.close();
});
