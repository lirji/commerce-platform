# 前端弹层与按钮统一：本地 Docker 部署结果

## 部署结果

用户明确授权“部署一下”后，于 2026-10-03 03:15 UTC 更新本机 Docker Desktop `desktop-linux` 的 `commerce-platform-app-1`，结果 **PASS**。访问入口为 <http://127.0.0.1:8602>。

| 项目             | 实际结果                                                                  |
| ---------------- | ------------------------------------------------------------------------- |
| 部署源版本       | `70d07407be55f22ab70d3cff7cb4c31ed558088e`                                |
| 镜像标签         | `commerce-platform:rev-70d0740`                                           |
| 镜像 ID          | `sha256:d0a944e3c3598e7f2fa72cc937b99758dd7acf55359b080a91d91a1a57206571` |
| 应用 JAR SHA-256 | `82ae715d81e415b78877816705cb7b7714730dc4049e9d52471f8d9ca5e7e89f`        |
| 容器健康         | `healthy`，`/actuator/health` 返回 `UP`                                   |
| 前端资源         | 61 个文件与当前构建逐字节一致                                             |
| 浏览器回归       | Docker 实际提供页面，13 PASS / 0 FAIL / 0 SKIP                            |
| 回滚版本         | 已保留 `commerce-platform:rev-753cba1`，无需回滚                          |

部署版本的精确 [main CI 37085563281](https://github.com/lirji/commerce-platform/actions/runs/37085563281) 已成功，包含后端 553 项测试（5 项既有条件跳过）及浏览器 43 PASS / 20 条件 SKIP。本次打包使用 `npm run build --prefix frontend` 与 `mvn -B -Pwith-ui package -DskipTests`；本地打包不重复声称运行了后端测试。

## 验证与保留

旧运行 JAR 与新 JAR 的 21,569 个非前端条目（展开依赖 JAR，比对条目内容，排除 MANIFEST 和目录）完全一致。运行容器中的 JAR 与本次构建 SHA-256 一致，容器镜像 ID 与构建标签对应 ID 一致。

`deploy/smoke.py` 验证健康、入口 HTML、JavaScript 资源以及匿名 `/v1/me` 的 401。浏览器使用正式 DTO 的测试边界验证真实 Docker 前端中的居中弹层、38px 按钮、移动端边界、关闭保护、列表焦点恢复、表单初值、商品详情与购物袋；这些测试不写入业务数据，不代表重新验收支付或真实授权。320px 购物袋在入场动画结束后另行截图复核通过。

现有数据库、地址加密密钥、全部容器环境变量值和 `dev-infra` 网络均保留，仅私密 Compose 配置的 `COMMERCE_IMAGE_TAG` 更新为新标签。未更改数据库迁移、重置数据、清理卷或更新其他项目容器。

## 证据与恢复

本地受控证据位于 `.local/frontend-modal-buttons/docker/`：`DEPLOYMENT_RESULT.json`、`artifact-proof.json`、`runtime-proof.json`、`smoke.json`、构建/部署日志及 `browser/browser-results.json`。私密配置和原有环境备份保持忽略，不提交凭据。

需要回滚时，在本项目目录以 `COMMERCE_IMAGE_TAG=rev-753cba1 docker compose --env-file .local/compose.env up -d --no-deps --no-build --wait --wait-timeout 180 app` 恢复已保留镜像，再执行 `python3 deploy/smoke.py`。镜像回滚不回滚业务数据；持久配置标签也应在获授权执行回滚时同步。未经授权不执行回滚或数据清理。

本记录及进度同步属于独立的文档交付；它们不会改变已部署产品版本。文档提交与远程交付终态追加到私密 `DEPLOYMENT_RESULT.json`，避免文档引用自身提交造成循环。
