# Phase 8 — Marketing Journey & Workflow Orchestration

Phase: 8

Final Status: **PHASE_8_COMPLETE_WITH_LIMITATIONS**（有界Journey与全部必需本地验收完成；Git/远程CI实际状态见交付记录）。

## Executive Summary

真实订单支付事件入组固定发布版本，持久WAIT后读取当前事实，以既有三值规则确定单路径，调用既有CREDIT权益，完成实例并经原事件链履约至AVAILABLE/唯一台账。动作、节点证据和检查点同库原子提交。真实kill、重启、两个JVM、旧新binary、热点与混合车道均验证通过。没有新服务、调度器、BPM或规则/权益系统。

## Starting Baseline

Phase7四笔提交11c7f37/a6b3b8d/40376b3/aa8bef1先正常推送远程main，核对实际aa8bef17343c9722b3d2f19a4f9d9a006d694ae1。Phase8从此创建feat/phase8-marketing-journey；一次计划确认后连续执行。基线、源码盘点见[00-baseline](00-baseline.md)、[01-inventory](01-current-journey-inventory.md)。没有改写历史迁移或Phase2–7证据。

## Phase 7 Compatibility

既有规则、营销版本、确定性竞争、CREDIT/COUPON发放、预算/额度、退款和权益恢复保持原责任边界。最终全套回归通过。真实OLD aa8bef1和NEW共享V44/V45互读、入组去重、恢复通过；旧节点执行不伪造完整trace。历史main CI四项陈旧UI断言由独立fix/ci-browser-contracts bdc2af7修复，最终新jar全24浏览器通过，产品UI未变。

## Current Journey Inventory

原平台已有32节点DAG、固定内容版本、审批发布、事件/手工/生命周期入组、持久WAIT、DECIDE、GRANT/COUPON/NOTIFY/END、频控、取消/隔离重试与共享公平车道。本轮补齐逐步证据、稳定图配置码、无副作用预览、发布固定引用重检、失败版本竞争保护、实际积压观测与规模/进程证明。完整盘点见01。

## Journey Architecture

Journey属于marketing-automation：图与版本、入组、单节点推进和history。Controller只协议，应用服务编排，JourneyGraph纯图/分支规则，JourneyActions显式已有动作registry，Mapper承担SQL。领域权益/券/会员事实继续由原owner写入；关系库权威。正式[架构](../../delivery/phase8-marketing-journey/BACKEND_ARCHITECTURE.md)、[契约](../../delivery/phase8-marketing-journey/CONTRACTS.md)、[切片](../../delivery/phase8-marketing-journey/IMPLEMENTATION_SLICES.md)为本轮增量来源。

## Journey Definition Model

保留原JSON schema1节点/触发与审批状态，不引入通用插件或图编译器。定义包含tenant、journeyId/version、store、UTC有效窗口、entry、有限nodes、maxDuration和可选频控；图版本创建后不可原地改内容。参见[02](02-journey-domain-model.md)。

## Journey Versioning

新发布只接纳新入组，在途实例永久固定原journeyVersion。pause停止新入组，在途继续；再次publish仍经引用/窗口校验。v1/v2共存、旧历史保留真实图、OLD/NEW覆盖程度已验证；没有在途迁移和动态改图。见[05](05-versioning-publication.md)。

## Graph Validation

有界32节点、唯一标识、起点存在、后继存在、全部可达、无循环、动作与规则配置合法；WAIT 1–604800秒、最大生命周期2592000秒。validate返回稳定ValidationCode；create/publish均调用相同校验，publish再次核对固定权益/券绑定。见[04](04-graph-validation.md)。

## Journey Instance Model

固定tenant/member/store/order/business trigger与定义版本，保存currentNode、steps、version、dueAt/deadline/attempts/status。领取短事务行锁与条件更新，成功影响行数必须1；没有sleep线程、内存计时器或新租约。合法状态与终止见[03](03-state-machines.md)。

## Step State Model

V44新增journey_step_execution和nullable trace_origin_version。键tenant/instance/transitionVersion，逻辑ordinal在重试时不变；记录节点、类型、UTC时间、WAIT资格、Truth、后继、outcome/actionRef/failureClass。EXECUTING仅事务内；成功/WAIT/停止和checkpoint一起提交；FAILED/DEFERRED/ISOLATED在回滚后受原版本保护记录。所有字段/表有中文SQL注释及PK/FK/CHECK约束。

## Trigger / Entry

沿用可信事件适配/手工/生命周期扫描；业务来源唯一键在tenant/journey/version范围。订单重复传输eventId而orderId相同仍只一实例；不同来源重入沿原可选频控。暂停/时间窗/会员开关拦截新入组；无泛化回溯入组。见[10](10-idempotency-recovery.md)。

## Wait Semantics

WAIT首次成功持久后继与绝对UTC dueAt，重启不会重新计时。到期为资格而非精确SLA；到期后短事务重新检查deadline、当前会员/退款等资格。超过deadline终止，即使due未来。真实25秒WAIT/SIGKILL及50k未来群证明。见[07](07-wait-semantics.md)。

## Condition / Branch

复用RuleDecisionPort/可信MemberRuleFacts，一次DECIDE只选择MATCH yes或NO_MATCH no；UNKNOWN显式终止RULE_UNKNOWN。WAIT之后读取当时事实，真实标签撤销走false且无权益。preview只读当前事实，不写命令或动作，WAIT返回FUTURE_DEPENDENT，不预测未来。见[08](08-condition-branch.md)。

## Action Integration

Map.of显式注册GRANT/COUPON/NOTIFY，未知动作无fallback；来源hash(instance/node)稳定。GRANT复用EntitlementApi，返回BENEFIT_ACCEPTED/真实grantId，AVAILABLE仍由原消费者产生；券由CouponApi，通知由原journey唯一表和频控。见[09](09-action-integration.md)。

## Rule / Marketing Reuse

不复制规则器、促销选取、额度、钱包/台账或发放治理。固定benefit/coupon版本，内嵌固定规则；不使用latest引用。新增图/动作扩展只在已批准节点集合内，未引入活动全引擎执行节点或外部Webhook。

## Idempotency

入组业务唯一键、实例行锁/transitionVersion、动作来源唯一、effect唯一、通知唯一与原Outbox/Inbox共同限定保障范围。同库动作/trace/checkpoint原子；不能称所有外部系统天然exactly-once。详细失败/幂等矩阵见10和[MATRICES](MATRICES.md)。

## Retry / Recovery

共享FailureClass/RetryPolicy：瞬时依赖DEFERRED不耗毒次数，永久失败有界退避五次ISOLATED；所有恢复受deadline。失败回滚后重锁原version，另worker已推进则旧错误跳过。管理员仅原节点retry/cancel，Commands与RecoveryAudit同事务；禁止整程replay，取消不逆转已提交业务效果。

## Crash / Restart

真实进程kill WAIT后重新启动两个JVM，checkpoint/wake不变；grant写入/stepFinish前临时owned trigger窗口kill调用JVM并释放owned MySQL连接，未提交权益/trace均0，另一JVM恢复唯一grant/ledger。测试包装另证明commit后Error不重放早期动作。进程/trigger均清理，产品无故障开关。见[11](11-crash-restart.md)。

## Multi-Instance Results

两个真实JVM同库竞争，只一份效果和正确检查点/历史；另一worker成功后旧失败不能附到新节点。没有把单JVM线程测试冒充多进程。见[12](12-multi-instance.md)。

## Runtime Fairness

复用TenantRotation/WorkLanes/EventWorker。每tenant≤20scan+5实例节点，200次尝试/500ms合作预算。hot5000/normal5，normal在0.726s完成、hot仍4991；同负载真实payment后台对账→order PAID→event fulfillment Inbox通过。运行时开始延迟journeys/payments/events本地最大15/61/17ms。

## Scale / Backlog Results

真实MySQL/调度器逐条执行，干净复测100 due=6.127s/16.32step/s，1000=48.759s/20.51step/s；10000=469.990s/21.28step/s。50k SQL合成future WAIT保持steps0，真实入组/WAIT语义另用纵向与进程测试证明。旧OR50k扫描50000行28ms；分状态/时间有界UNION同场景PK投影0行约0.06ms，完整Mapper计划归档。V45扩展global due/deadline索引。原受探针互扰1000结果保留、不作主容量；见[13](13-scale-backlog.md)、[scale.csv](results/scale.csv)。

## Security / Authorization

tenant来自Actor，不由输入选择；admin配置/发布/preview/recovery，会员只本人实例/history。新增精确history允许路径，未开放管理接口。跨tenant/另一member与401/403/404回归通过；平台聚合仍需专用运维能力。见[14](14-security.md)。

## Audit / Explainability

history短只读REPEATABLE_READ一次快照给固定原图、触发来源和有界steps页，保留decision/actionRef/失败分类/恢复审计。COMPLETE仅实际新trace覆盖完整成功步数，OLD混跑PARTIAL、旧入组LEGACY_PARTIAL。可解释路径和结果，不存完整敏感Facts，不承诺精确重演旧事实（JRN17限制）。

## Observability / Alerts

journeys backlog supplier现为实际到期实例+生命周期scan、oldestAge和quarantine；5秒聚合缓存，无tenant标签。复用commerce.lanes.*和LANE_BACKLOG_AGE/ROTATION_SLOW/STARVATION/QUARANTINE_GROWTH/DEPENDENCY_UNAVAILABLE。固定版本/node/actionRef关联排查、隔离重试/回退/补偿手册见[15](15-observability-runbook.md)。

## Capacity Model

入组E、成功步N、额外失败R，则trace增长E*N+R；成功32步内，截止停止可33，DEFERRED受deadline但仍增加记录。现场页分摊粗估659B/instance、149B/END密集trace；action/Outbox/审计另算。没有真实生产入组率/节点分布/保留期，不能虚构日增长/峰值或SLO。Backlog统计随active量增长；500ms不是驱动/服务端强制抢占。

## Tests

S1 87、S2 94、故障窄83、扩展纵向86项零失败。最终新增Graph4/Recovery7/Persisted相关7场景，全部在完整真实DB构建内通过。J1–18与JRN1–20见MATRICES；每片TEST_RESULT、[REVIEW](../../delivery/phase8-marketing-journey/REVIEW.md)、[QA](../../delivery/phase8-marketing-journey/QA_RESULT.md)提供证据映射。

## Mutation / Negative Tests

反转MATCH、删除WAIT资格、删除失败version守卫三种mutation均被指定业务断言捕获（非编译错误）；finally源hash恢复，之后最终clean构建通过。非法图/越权/过期引用/未知replay/永久失败负例保留。脚本与结果不进入产品包。

## Regression Results

最终scripts/build.sh：app297/0 failures/0 errors/5 configured benchmark skips；architecture3、marketing27、order45、kernel3全部成功；tsc/Vite、UI/API入jar成功。npm audit高风险门禁零漏洞，diff check PASS，hygiene BLOCKING=0。无Java/Python canonical formatter/static配置，明确工具限制。jar SHA256 74a16e41e86ff98928fc00ef09444dca097cb845bc735a535c4e2113b6c9baa9。见[16](16-regression.md)。

## Browser Results

最终jar受影响4/4（26.8s）与全量24/24（1.2min）。覆盖真购买支付履约退款/权益冲正、低代码规则审批窄屏、手工Journey站内信、生日发券及全部既有流程。四项陈旧UI测试断言修复独立提交，未移除业务断言或更改产品行为。远程CI另绑定不可变Git ref，不能用本地PASS冒充远程结果。

## Known Limitations

本地共享dev_infra测量不认证生产持续容量/百万计时器。历史没有完整Facts快照；旧执行覆盖部分；Journey保留期未批准，不新增破坏性清理。Backlog聚合有成本；时间预算合作执行。早期故障探针曾干扰首轮1000测量，保留噪声并隔离复测。Phase7偶发历史Journey失败本轮未复现，不声称已定位所有历史根因。

## Blocked / Deferred Product Decisions

无阻断必需目标的产品决定。刻意延后在途迁移、full replay、fan-out/in、child Journey、人工任务、事件等待、任意脚本/外部Webhook和自动补偿。生产SLO/保留期限与扩容阈值须有真实业务依据；超出有界in-app模型再评估引擎，不预先引入平台。

## Changed Files

JourneyApi/Graph/Actions/Service/Mapper/XML，V44/V45，JourneyController与精确SecurityConfiguration路径，Graph/Recovery/Persisted真实测试；正式设计/契约/切片、证据脚本与结果、doc-map、CODEX_PROGRESS。另独立四项frontend/tests陈旧断言修复。清单见[changed-files](results/changed-files.md)。机密/private日志不提交。

## Evidence Location

本目录00–16、MATRICES、results和[scripts说明](scripts/README.md)。docs/delivery/phase8-marketing-journey为设计、各片验证、QA/Review、Git/CI权威记录；根CODEX_PROGRESS为恢复入口。`.local`只保留大日志/私密配置/旧binary，不将生成凭据归档。

## Commit Boundary Recommendation

以已批准且共同验证的S1–S3图/trace/动作/恢复/车道纵向单元提交（同Service/API/Mapper和schema共同验证），S4/S5可复跑证据与验收单独提交；独立CI测试适配保留原任务分支/提交。按用户最新AGENTS#8正常合并推送main，不强推、不部署。实际SHA、远程gate和后续文档提交见[DELIVERY_RESULT](../../delivery/phase8-marketing-journey/DELIVERY_RESULT.md)。

## Next Recommended Phase

以真实业务负载建立Journey容量/SLO和保留治理，并针对确有产品需求的下一动作定义版本/幂等/失败/补偿契约。保持同库可恢复模型，暂不建设通用工作流平台。
