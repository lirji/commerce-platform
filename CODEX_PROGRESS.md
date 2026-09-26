# Codex Progress

## 任务目标

按提示词第 4、5 节优化会员端与登录页视觉：沿用 React / Vite / Ant Design / hash 路由，明亮商业感，不要落地页或经营台式卡片。

## 当前状态

店铺货架与登录右侧入口区已重做。`tsc --noEmit` 通过；8601 登录烟测可见「安全进入」面板、`访问凭据`、`进入平台`。完整 Playwright 仍 `UNVERIFIED`。未 commit。

## 已完成

- 店铺页：紧凑顶栏、分类频道、高图红价货架；去掉 hero / MEMBER STORE。
- 登录右侧：去掉 Ant Card + WELCOME BACK；改为 `login-panel`（清晰层级、raised 输入、焦点环、会话说明列表）。
- 截图：`.local/shop-visual-smoke/{login,login-mobile,shop,cart,orders,coupons}.png`。

## 未完成

- 完整 Playwright 回归 `UNVERIFIED`。
- 8602 仍是旧包；新 UI 只在 8601。
- 提示词第 6 节之后未做。
- 未 commit / push / 部署。

## 下一步

1. 打开 http://127.0.0.1:8601 看登录与店铺。
2. 继续第 6 节或入库时再说。

## 恢复 Prompt

读 `CODEX_PROGRESS.md`。登录入口在 `App.tsx` 的 `login-panel` + `style.css`；E2E 仍认「访问凭据」「进入平台」。
