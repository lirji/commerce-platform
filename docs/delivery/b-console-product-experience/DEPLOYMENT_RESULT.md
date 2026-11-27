# 本机 Docker 部署结果

2026-11-27 16:24（UTC+08:00）已将交易中心 B 端更新到本机 [8602](http://127.0.0.1:8602)。容器 `commerce-platform-profile-app-1` 为 healthy。部署源码为 `478ce42e2206e567b6e443038c39399841bd887d`，已快进发布到远程 main。[部署前完整 CI](https://github.com/lirji/commerce-platform/actions/runs/45964314462) · [同提交主线 CI](https://github.com/lirji/commerce-platform/actions/runs/45965302134)

| 项目 | 实际结果 |
| --- | --- |
| 运行镜像 | `commerce-platform:rev-478ce42e2206`，ID `sha256:88bccace8b7d17a5b150079bd46525b75c1be427cf6379b75a7c066efd0fdd25` |
| 应用归档 | 容器内 JAR SHA256 为 `3dcf97ccac22405d686f285e4e5201ad741c85511dc4e4c195ad2a074f83a2a4`。新前端与该提交构建产物逐文件一致 |
| 后端与数据库 | 588 个非前端归档条目和 92 份迁移脚本与部署前逐字节一致。Flyway 核验为 V93，无待执行迁移 |
| 服务配置 | Compose 单服务更新，端口、网络、运行环境、权限及资源限制复核一致。应用健康检查 UP，匿名 API 返回 401 |
| 前端与浏览器 | 76 个同源文件逐项 HTTP 验证通过。29 个旧运营页面在 320–1920 px 进行 91 次布局核对，128 次可见读取全部 200，另实测指标切换、状态筛选与重置、窄屏订单详情和返回。浏览器发出 0 次业务写请求 |
| 回退 | 旧镜像 `local/operation-audit-commerce-app:ed1b7acb1320` 已保留，当前版本健康，实际回退次数为 0 |

本机运行沿用原凭据登录模式。中央授权和协作页面使用独立预览实例进行契约和响应式验证。临时 MySQL 和独立 Spring Boot 回归实例已经停止。

本轮镜像通过 `deployment/image-only.compose.json` 追加到原 Compose 配置链。容器的 Compose 标签记录实际配置文件顺序。

本机证据位于 `.local/b-console-product-experience/`，包括容器和 HTTP 指纹回执 `deployment/deployment-result.json`、浏览器页面矩阵 `docker-live-matrix.json`、交互结果 `deployment/read-only-browser.json` 和截图目录 `docker-live/`。
