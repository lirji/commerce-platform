# 紧凑弹窗调整与 Docker 部署

用户反馈弹窗过大后，已将普通详情默认宽度收紧为560px，编辑表单通常600–640px，表格/关系图760px；商品详情720px、购物袋520px、导航400px。旅程不再默认展开，主动展开共享弹层为960px。标题16px、圆角12px，减少表单和头尾留白；手机两侧留白16px。长内容内部滚动，保存/取消入口固定，按钮仍统一38px。

320px购物袋实际高度从约696px缩至514px；手机商品图片和缺图占位收紧，步骤横向排列且标题在图标下方。没有变更API、权限、按钮尺寸、关闭保护、请求中保护或业务数据。

部署源版本为 `7870aa1878d0a55952f56edb6c0968cb28145da8`，精确 [main CI 37093269697](https://github.com/lirji/commerce-platform/actions/runs/37093269697) 与任务分支CI均SUCCESS。后端553项测试（548 PASS / 5条件SKIP）、浏览器43 PASS / 20条件SKIP，无失败或错误。本地20经营/会员回归、19中央回归均PASS，中央36路由覆盖1440/390/320px。

2026-10-03 UTC已更新本机 `desktop-linux/commerce-platform-app-1`，镜像 `commerce-platform:rev-7870aa1`，入口 <http://127.0.0.1:8602>。容器healthy，应用健康UP，匿名API401；运行JAR与构建SHA一致，61个实际HTTP前端资源逐字节一致，Docker页面13项回归PASS。新旧21,569个非前端展开条目完全一致。本地打包使用 `mvn -B -Pwith-ui package -DskipTests`，后端测试结果来自上述精确CI。

数据库、全部环境变量值、地址密钥和dev-infra网络保留，仅私密Compose镜像标签更新。未变更迁移、灌数据、清空数据或卷；旧 `rev-70d0740` 镜像保留且无需回滚。自有18601/18602/18603预览已停止，现有8602容器保持运行。

详细证据和版本指纹位于 `.local/compact-modals/DEPLOYMENT_RESULT.json`、`runtime-proof.json`、`artifact-proof.json`、`ci-counts.json` 及浏览器报告；该目录保持忽略。展示回归使用正式DTO测试边界，不冒充真实资金或授权验收。首轮preview后端缺失与源码导入测试环境错误的证据保留，匹配环境后全部必要检查通过。

本文件及进度收尾为文档提交，产品仍部署上述已验证版本。文档Git交付和新文档CI状态追加到私密最终回执，不因文档提交不同而重复部署。
