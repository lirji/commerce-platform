# 本机 Docker 部署结果

已部署并验证：**http://127.0.0.1:8602**。实际镜像为 `commerce-platform:rev-2851fc1`，源码引用 `2851fc151da80fa5ab2749f2863493f36b3585b6`，部署完成时间 `2026-10-04T01:24:12.708905+00:00`（UTC）。容器 `commerce-platform-app-1` 为 `healthy`，应用健康为 `UP`，匿名业务请求返回 401。结构化回执见 [DEPLOYMENT_RESULT.json](DEPLOYMENT_RESULT.json)。

|验证项|实际结果|
|---|---|
|部署前精确 CI|[main CI37165427241](https://github.com/lirji/commerce-platform/actions/runs/37165427241)，2851fc1，SUCCESS|
|本轮全新隔离 MySQL 完整验证|75 套件、557 项：552 PASS / 5 条件 SKIP / 0 FAIL、ERROR|
|同源制品|JAR 内 61 个前端文件与 dist 逐字节一致；容器运行 JAR 与宿主制品 SHA256 一致；全部 61 个实际 HTTP 资源一致|
|固定授权 SDK|109b1edb7fb61fa2e2fc2fa0ec726b42438f5810；SDK、protocol 实际嵌套 JAR 与固定 checkout 相同，使用本轮隔离 Maven 缓存|
|部署后 Chromium 交互|10 PASS / 1 条件 SKIP / 0 FAIL、flaky；覆盖快速输入/预览/重新打开、草稿筛选、读取重试、键盘、焦点、窄屏及趋势/旅程图|
|真实现有数据库界面|管理 29、会员 7，共 36 入口 × 1440/390/320px；61 GET 全部 200，关键词真实查询 PASS，0 JS 错误、0 业务写命令；20 张页面族截图|
|真实只读页面预览|两个只读 POST 预览均 200；页面标识保持，显示两条实际数据，未保存、审批或发布|
|运行库迁移|实际 Flyway 版本 72，up-to-date / No migration necessary；新旧源码迁移目录无差异|

运行镜像 ID：`sha256:f16e667ab554f064c0680189690c67a321e641228479fbb0aab44c4edda23d92`。运行 JAR SHA256：`d990db532228116a9a432428e763c7222dc8f189367ff2dd5fd74158fb770727`。校验使用实际运行容器和 HTTP 返回字节，同一实施者完成，未冒充独立验证。

保留原 `commerce_local`、数据库账号与地址加密密钥，运行环境值、8602 回环端口、dev-infra 网络、只读根目录、非 root 用户和资源限制均与部署前一致。两份私密配置中仅 `.local/compose.env` 的 `COMMERCE_IMAGE_TAG` 更新；没有运行库 seed、清库或手工业务保存/发布。既有 worker 按原设置运行，不能据此宣称数据库所有业务内容静止不变。没有切换中央 IAM 模式或生产环境，条件跳过项不计为真实 SSO/PKCE 验收。

本地首轮验证在已有测试库完成 219 项时出现 1 项支付重试失败，周期调度长期扫描历史租户。原测试库有 111657 条会员、876 条周期策略、132081 条事件；未清理旧数据，失败和中止报告保留。随后 Docker 管理 API 和原服务健康多次超时，临时测试实例也无法正常初始化/停止。用户明确允许重启 Docker Desktop 并继续部署；普通重启超时后，使用官方 `docker desktop stop --force` / `start` 恢复。根据此前运行观察和退出时间匹配，原样启动了此次重启停止的 5 个既有实例，没有重建或改配置。共享 Maven 缓存的 SDK 字节也发现漂移，改用本轮独立缓存重新安装固定源码；没有改 Auth 工作区或共享缓存。恢复后在复用 dev-infra 相同镜像的独占临时 MySQL 上完整 clean verify 通过，首轮失败类 104 项和周期调度 5 项均 PASS；临时实例已确认移除。

旧稳定镜像 `commerce-platform:rev-f039e12` 和原私密快照保留。本轮策略在健康、制品或原配置校验失败时恢复此同迁移版本旧镜像，实际未触发回退；代码回退不能撤销已经提交的业务效果。没有删卷、重建共享基础设施、清理旧工作树或新建任务工作树。

原始构建/失败/恢复/制品/浏览器证据和凭据分开保存在忽略的 `.local/frontend-task-usability/deployment/`，6 张关键原图已实际查看，含真实 320px 人群/会员成长、1440px 页面列表、编辑字段、真实预览数据及夹具详情焦点。截图输出目录偏移已按本轮文件时间核对并逐文件移动回项目，字节不变；父目录空证据目录保留。私有 SDK checkout、Maven 缓存、JAR 和回退镜像继续保留以便复核。后续部署文档提交不修改产品源码或重建该镜像，最终 Git/main/精确 CI 回执保存在 `.local/frontend-task-usability/deployment/documentation-delivery.json`，避免文档引用自身造成循环提交。
