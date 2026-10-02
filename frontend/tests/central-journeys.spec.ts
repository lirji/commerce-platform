import { test, expect } from "@playwright/test";
import fs from "node:fs";
import assert from "node:assert/strict";

// 此用例只对显式指定的真实隔离fixture执行；不写业务命令，不以页面夹具替代PKCE。
const fixturePath = process.env.COMMERCE_JOURNEY_FIXTURE;
test("真实旅程PKCE与窄屏未保存输入保护", async ({ page: p }, testInfo) => {
  test.skip(!fixturePath, "需要本任务真实隔离IdP/Auth/Commerce fixture");
  const f = JSON.parse(fs.readFileSync(fixturePath!, "utf8"));
  const base = process.env.COMMERCE_UI_URL ?? "";
  for (const url of [new URL(base), new URL(f.authority)]) {
    assert(
      ["127.0.0.1", "localhost"].includes(url.hostname),
      "仅允许明确本机隔离目标",
    );
  }
  assert(f.database.startsWith("commerce_journey_real_"), "必须是任务独占库");
  const checks: string[] = [],
    shots: string[] = [];
  await p.setViewportSize({ width: 320, height: 844 });

  await p.goto(base + "/operations/journeys?tenant_id=" + f.tenant);
  await p.getByRole("button", { name: "企业登录", exact: true }).click();
  await p.waitForURL(f.authority + "/**");
  await p.locator("#username").fill(f.user.name);
  await p.locator("#password").fill(f.user.password);
  await p.getByRole("button", { name: "Sign In", exact: true }).click();
  await p.waitForURL(base + "/operations/journeys?**");
  await expect(
    p.getByRole("button", { name: "创建版本", exact: true }),
  ).toBeEnabled({ timeout: 30000 });
  await p.getByRole("button", { name: "创建版本", exact: true }).click();
  const m = p.getByRole("dialog");
  await m.getByLabel("名称", { exact: true }).fill("只读交互核对，不提交");
  await p.keyboard.press("Tab");
  assert(
    await m.evaluate((el) => el.contains(document.activeElement)),
    "focus leaves modal",
  );
  checks.push("keyboard focus remains in current modal");
  await p.keyboard.press("Escape");
  await expect(
    p.getByRole("dialog", { name: "放弃未保存的输入？", exact: true }),
  ).toBeVisible();
  await p.getByRole("button", { name: "继续编辑", exact: true }).click();
  await expect(m).toBeVisible();
  await expect(m.getByLabel("名称", { exact: true })).toHaveValue(
    "只读交互核对，不提交",
  );
  checks.push("Escape asks dirty confirmation; dismiss retains actual input");
  await m
    .locator(".ant-modal-body")
    .evaluate((el) => (el.scrollTop = el.scrollHeight));
  await p.waitForTimeout(650);
  await expect(
    m.getByRole("button", { name: "提交实际操作", exact: true }),
  ).toBeInViewport();
  await expect(
    m.locator("form").getByRole("button", { name: "关闭", exact: true }),
  ).toBeInViewport();
  await p.waitForTimeout(650);
  const photo = testInfo.outputPath("journey-final-scroll-footer-320.png");
  await p.screenshot({ path: photo, fullPage: true, animations: "disabled" });
  shots.push(photo);
  checks.push(
    "320 width body scroll reaches footer actions without document horizontal overflow",
  );
  assert(
    await p.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth + 1,
    ),
  );
  await m
    .locator("form")
    .getByRole("button", { name: "关闭", exact: true })
    .click();
  await expect(
    p.getByRole("dialog", { name: "放弃未保存的输入？", exact: true }),
  ).toBeVisible();
  await p.getByRole("button", { name: "放弃输入", exact: true }).click();
  await expect(p.getByRole("dialog")).toHaveCount(0);
  checks.push("confirmed discard closes dirty form without business command");
  await testInfo.attach("journey-interaction", {
    body: JSON.stringify({ checks, shots }),
    contentType: "application/json",
  });
});
