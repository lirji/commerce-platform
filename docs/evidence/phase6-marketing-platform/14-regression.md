# P6.11 回归、负例与构建证据

## 服务端与干净产物

- 修改前 `./scripts/verify.sh`：`commerce-app` 264 run / 0 failure / 5 skipped，架构 3/3。
- 核心闭环完成后全量 `./scripts/verify.sh`：`commerce-app` 269 run / 0 failure / 5 skipped，架构 3/3；新增三条纵向测试、双实例和并发发布均通过。
- 加入预览/发布负例和用例能力后，第一次 `./scripts/build.sh` 仅有一条**测试断言**失败：过期人群发布按现有契约返回 409，测试误写 400。改正断言后重新干净执行 `./scripts/build.sh`：`commerce-app` 271 run / 0 failure / 5 skipped，架构 3/3，全部 Maven 模块 `BUILD SUCCESS`，前端 `npm ci`/构建通过，打包 jar 检查存在 `BOOT-INF/classes/static/index.html`。
- 实际 MySQL 8.4 隔离测试库执行了 41 个 Flyway 迁移，新增 V41 成功；测试内含受限规则/人群、权限、额度、事件恢复、重放安全、支付/订单与 Phase 5 一致性回归。

## 负例/变异候选保护

| 被破坏的保护 | 会失败的可执行检查 |
| --- | --- |
| 绕过人群 HIT、用 NOT 反转失效人群 | `staleAudienceIsUnknownAndCannotBeRescuedByNot`、`audienceMissAndCrossTenantReferenceFailClosed` |
| 发布失效引用或重复规则 | `publicationRechecksAudienceAndRejectsDuplicateRuleBranches`、`reusableRuleMustBePublishedAndCannotChangeFrozenCampaign` |
| 用当前配置重算历史订单 | `marketingExecutionKeepsVersionsAcrossPublishAndRollback` |
| 忽略参与幂等或权益唯一键 | `marketingExecutionTracksRealOrderGrantAndTenantBoundary`、`entitlementIsReservedThenGrantedOnceAfterTrustedPayment` |
| 移除额度条件更新 | `lastEntitlementQuotaLetsOnlyOneConcurrentOrderReserve` |
| 把受理当已授予、或丢失隔离失败 | `marketingExecutionShowsIsolatedGrantAndUsesExistingRecovery` |
| 把投影错误声明为可历史重放 | `ReplayTest.everyConsumerIsClassifiedAndOnlyThePureProjectionIsReplayable`（首轮确实拦截，已修正） |

本轮采用非法配置/状态/并发负例及原有 Replay 门禁；没有对生产源码做临时字节变异。首轮 Replay 门禁拦下过宽的重放声明，修正后全量通过。

## 浏览器

浏览器运行使用 `scripts/browser-affected.sh`、干净打包 jar、隔离 MySQL 与新租户夹具，**2/2 通过**：真实会员订单/沙箱支付/履约/售后/权益冲正 14.1 秒，规则/低代码预览审批发布及窄屏 3.3 秒。无 `NEW_PHASE6_REGRESSION`。原始日志与 JSON 保存在忽略的 `.local/phase6-browser/`，不包含在交付源码中；应用日志退出时脱敏临时开发口令。Phase 5 全套浏览器基线为 20/24，目录经营、定向发券、会员行为、会员周期四项是 `KNOWN_EXISTING`；本次只检查受影响的订单权益旅程与规则/低代码配置旅程，未重跑这四项。远程 CI、提交/推送和生产部署未执行，符合本轮范围。
