import { expect, type Page } from "@playwright/test";

/** 比较真实渲染尺寸，覆盖页面入口、表格操作和通过 Portal 渲染的弹层按钮。 */
export async function assertButtonSizes(page: Page) {
  const buttons = await page
    .locator(".ant-btn:visible")
    .evaluateAll((elements) =>
      elements.map((element) => {
        const style = getComputedStyle(element);
        return {
          text: element.textContent?.trim(),
          height: element.getBoundingClientRect().height,
          font: style.fontSize,
          radius: style.borderRadius,
          padding: style.paddingInlineStart,
          icon: element.classList.contains("ant-btn-icon-only"),
        };
      }),
    );
  expect(buttons.length).toBeGreaterThan(0);
  for (const button of buttons) {
    expect(button.height, button.text).toBe(32);
    expect(button.font, button.text).toBe("14px");
    expect(button.radius, button.text).toBe("6px");
    expect(button.padding, button.text).toBe(button.icon ? "0px" : "10px");
  }
}

/** 断言实际位置和视口边界，避免仅凭组件名判断弹层已居中。 */
export async function assertCentered(page: Page) {
  const dialog = page.getByRole("dialog").last();
  await expect(dialog).toBeVisible();
  // 等入场动画结束再核对位置和截图，避免记录只有遮罩、内容仍透明的过渡帧。
  await expect
    .poll(() =>
      dialog.evaluate((element) => {
        const style = getComputedStyle(element);
        return style.opacity === "1" && !element.className.includes("zoom");
      }),
    )
    .toBe(true);
  await expect(page.locator(".ant-drawer")).toHaveCount(0);
  const box = (await dialog.boundingBox())!;
  const viewport = page.viewportSize()!;
  expect(
    Math.abs(box.x + box.width / 2 - viewport.width / 2),
  ).toBeLessThanOrEqual(2);
  expect(
    Math.abs(box.y + box.height / 2 - viewport.height / 2),
  ).toBeLessThanOrEqual(2);
  expect(box.x).toBeGreaterThanOrEqual(0);
  expect(box.y).toBeGreaterThanOrEqual(0);
  expect(box.x + box.width).toBeLessThanOrEqual(viewport.width);
  expect(box.y + box.height).toBeLessThanOrEqual(viewport.height);
}
