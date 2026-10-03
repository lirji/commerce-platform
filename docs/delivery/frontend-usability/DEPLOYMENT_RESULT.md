# 全站前端可读性改造交付

U1—U5完成：运营和会员页面共用真实筛选、游标上一页/首页/下一页与刷新恢复；补齐主体、归属、状态、金额、时间和关联字段；中文状态、空值与0区分，未知状态保留原代码。手机筛选减少纵向占用，会员主要状态随名称显示，宽表可滑动查看完整字段和操作。按钮保持38px，抽屉继续使用紧凑居中弹层，保留关闭、未保存和未知命令结果保护。

两个实施提交已正常合入并推送main：`322ca28`（U2兼容只读查询）和 `aeed09efb291e9120e2ad05d14280caee18e7905`（U1/U3/U4前端与验收记录）。[精确main CI37097514226](https://github.com/lirji/commerce-platform/actions/runs/37097514226) 与 [任务分支CI37097510158](https://github.com/lirji/commerce-platform/actions/runs/37097510158) 均completed/SUCCESS。完整CI证据：74套件554项，549 PASS / 5既有条件SKIP，0失败/错误；真实浏览器45 PASS / 21条件SKIP，无失败或flaky。中央边界19回归及36路由三种屏宽另在本地通过，不能把条件跳过说成已验证真实SSO。

2026-10-03 04:51:45 UTC已更新本机 `desktop-linux/commerce-platform-app-1`，入口 <http://127.0.0.1:8602>。镜像为 `commerce-platform:rev-aeed09e`，实际镜像ID `sha256:6c34abdfba5434730c5ccfa38820472b28db3265a4832336798ffb0a7e2eab47`，OCI revision与上述源版本一致。容器healthy，应用健康UP，匿名API401；运行JAR SHA256 `950b804c81bb9a450cc148449c32a1951b2f1d5d2798991410f4252aef415ea1` 与验证后打包一致，61个实际HTTP前端文件逐字节一致。

Docker上另完成33项真实只读查询，使用已有本机演示凭据读取 `commerce_local`，只发送GET，没有灌数据或调用业务命令。经营/会员/弹层/筛选/分页的Docker浏览器22项PASS，中央SSO条件用例1项SKIP；这些界面用例明确使用正式DTO测试边界，真实SQL与权限验证分别来自该33项检查及MySQL集成测试。

本次无数据库迁移、数据重置或新写入流程。数据库、全部容器环境值、地址密钥、端口和dev-infra网络与部署前一致；后台worker设置保持原值。旧稳定 `commerce-platform:rev-7870aa1` 镜像保留，未触发回滚；若健康/制品检查失败，部署步骤可恢复该旧镜像，不修改数据。

私密回执位于 `.local/frontend-usability/`：`CI_RESULT.json`、`ci-counts.json`、`artifact-proof.json`、`runtime-proof.json`、`readonly-runtime-smoke.json`、`docker-browser/browser-results.json`、`DEPLOYMENT_RESULT.json`。当前源码与测试指纹见 [TEST_RESULT](TEST_RESULT.md)，代码复核见 [REVIEW](REVIEW.md)，查询能力见 [补充契约](../../design/frontend-usability-queries.md)。旧失败证据保留。

自有18601/18602预览已停止，8602 Docker保持运行。本任务未新建工作树；私密凭据、制品和验收证据及已有历史工作树保留，没有未经授权的清理目录。交付收尾只变更记录，最终文档Git回执保存在私密delivery.json；部署产品继续绑定已通过精确CI的aeed09e，不因文档提交不同重复部署。
