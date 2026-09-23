# Codex Progress

## 任务目标

把本轮前端列表/详情面板排版部署到本地 Docker 8602，不改生产、不覆盖可回滚的 LP11 镜像。

## 当前状态

8602 已更新会员档案操作栏：行内只留「会员详情 / 更多」，资料与状态进菜单。镜像 `commerce-platform:ui-panel` `sha256:0c03a18353db78422646abfbfb37f9cbae05194c5d219a44d2f257962c00cd03`，health UP。未 commit。

## 已完成

- 前端 `npm run build` 后写入 jar 静态目录；新 jar SHA256 `358d10f3467b6f22fc748f356244cb9924266aa4cf3258876b6398645b301c61`。
- 镜像 `commerce-platform:ui-panel`，ID `sha256:165aae3d10f411f6be6409a9e8ba193ea19142e975f71b6333d4ef059615533d`。
- Compose 标签改为 `ui-panel`；容器 `commerce-platform-app-1` running/healthy，入口 http://127.0.0.1:8602。
- `deploy/smoke.py`：health UP、UI `/assets/index-C0E6-8De.js` 200、匿名 `/v1/me` 401。
- 回滚镜像仍在：`commerce-platform:lp11-701b4f1` / `66b523270dfa`。

## 未完成

- 完整 Playwright 回归 `UNVERIFIED`。
- 未入库 Git，未部署生产。

## 下一步

1. 浏览器打开 http://127.0.0.1:8602 查看新排版。
2. 需要回滚时切回 `commerce-platform:lp11-701b4f1`，不要用镜像回退撤销已发生业务。
3. 要入库再授权 commit。

## 恢复 Prompt

读取 `CODEX_PROGRESS.md` 与 `.local/ui-panel-deploy.json`。8602 当前是前端面板镜像 `ui-panel`，后端字节来自 LP11 jar `0b8b7aadb862b1783718bc34c92f99fdb52cb412b41bc81994017c5020751bad`。
