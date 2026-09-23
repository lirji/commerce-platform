# Codex Progress

## 任务目标

按 DDD 与模块化单体建设统一电商平台；覆盖会员、商家、店铺、商品、营销、交易、订单、支付、履约和售后，并建设活动、人群、规则、权益、优惠、旅程和低代码运营。用户于2026-09-23明确选择新建设任务，参考现有仓库；旧规则迁移继续保留其门禁。

## 已完成

- 当前连续执行授权：用户要求按S4–S10走到整个计划完成；真实外部联调明确后置，不以缺外部凭据暂停内部建设。
- S4已通过：真实MySQL8.4.11/HTTP、主数据、活动发布、持久报价、幂等/权限/回滚/并发/重启验证；全量81测试通过。分支feat/persisted-commerce-loop，证据docs/evidence/s4/。

- 只读扫描重点项目及源码，保存11个来源文件的指纹；保留旧进度快照。
- 总体方案、能力地图、营销设计、技术基线、C1–C4契约、S0–S10实施路线和风险登记已落盘。
- S1：Java 21领域内核；精确Money、有界条件树与三值逻辑、单活动固定减免择优、可解释结果、精确行分摊。
- S2：集中订单状态机；支付中取消进入CLOSING，可信支付/未支付事实分别推进；合法迁移与版本检查。
- S3：mvn -o -B verify退出0；72测试通过，0失败/错误/跳过；含500组分摊样本、42种状态事件组合和真实class依赖检查。
- 证据、构建日志、源码摘要见docs/evidence/；当前结构化进度见docs/PROGRESS_STATE.json。

## 已修改文件

- 新建commerce-platform内的pom.xml及shared-kernel、marketing、order、architecture-tests四模块源码/测试。
- README.md、docs/design/unified-commerce/、docs/evidence/、docs/PROGRESS_STATE.json、.engineering/bootstrap/PROJECT_BOOTSTRAP_REPORT.json、本文件。
- 工作区根CODEX_PROGRESS.md更新为当前任务指针；旧内容保存在docs/evidence/previous-workspace-progress.md。
- 没有修改既有项目源码、共享数据库或旧迁移计划。

## 未完成

- S5–S7：库存/权益预占与订单事务、支付渠道适配、Outbox/Inbox、履约、退款及补偿。
- S8–S10：人群来源与新鲜度、优惠叠加/券/预算、权益履约、旅程持久运行、低代码与真实前端/种子/部署验证。
- 全平台边界就绪、生产容量、故障恢复、数据库并发均未验证。当前是领域库，不是可部署电商应用。

## 当前问题

- 所有后续领域仍需逐切片冻结契约，不能直接复制旧仓；单店/CNY/单活动是首批范围。
- 初始代码提交5e5d3bd已从feat/unified-commerce-kernel进入main并推送origin，远程main SHA核验一致。交付记录见docs/evidence/DELIVERY_RESULT.md；未执行部署，远程CI未核验。
- 当前无失败测试、无后台构建进程。参考仓dirty保留。
- 旧EVOLUTION-rules v3仍BLOCKED；其限制只继续作用于旧迁移，不代表新建设内核未完成。

## 下一步建议

1. S4已完成，继续S5报价消费/库存预占/订单持久化；真实外部联调后置，保留端口与明确沙箱。
2. 建立版本化DDL（表/列中文注释）、MyBatis Mapper、应用事务、真实API和隔离数据库集成测试，验证重启回放/租户隔离/幂等。
3. 再推进S5订单预占闭环与S6支付事件；正式渠道与生产部署需明确目标，不以模拟成功冒充真实完成。

## 恢复 Prompt

请读取commerce-platform/CODEX_PROGRESS.md、docs/PROGRESS_STATE.json和docs/design/unified-commerce/IMPLEMENTATION_SLICES.md，从S5继续建设统一电商平台。保留已通过的S1–S3与源仓用户改动；旧规则迁移门禁保持不变。不要重新扫描全部项目或重做治理工具，不要把领域内核测试当成数据库/渠道/整个平台验收。按已授权的新建任务逐片实现、验证并更新进度，不要反复询问是否继续。
