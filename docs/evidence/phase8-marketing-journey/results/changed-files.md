# Phase 8 改动与验证指纹

基线aa8bef1；无无关工作树修改。验证后没有产品Java/SQL变更；下列当前源码SHA256用于revision fence，与最终clean jar记录共同核对。

| 源码 | SHA256 |
|---|---|
| `commerce-app/src/main/java/com/lrj/commerce/app/configuration/security/SecurityConfiguration.java` | f9f4e53901ab6ff478adc53579c53f10e713466c68d27362f508a7c073cb4d09 |
| `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/journey/JourneyController.java` | 66c4b12cdc4365d597d34aaadb484ea0b65bb666edd79c808af2ebb6be5261ad |
| `commerce-app/src/main/resources/db/migration/V44__journey_step_execution_history.sql` | d46c98b4830fcf852fb288b35023ef4e11af072e24cddc77ba740e4bafac67d7 |
| `commerce-app/src/main/resources/db/migration/V45__journey_due_discovery_indexes.sql` | 3cf4dfc6195167eedbf21e6db7746b8b3d46dc029fec0dc25f9a05e7cdcca2c5 |
| `commerce-app/src/test/java/com/lrj/commerce/app/JourneyGraphTest.java` | 0ad69779dcf84f84180eddeac00c1e78c5b598648251f50cf2125ffd7a0e4cb3 |
| `commerce-app/src/test/java/com/lrj/commerce/app/JourneyRecoveryTest.java` | 72bce908dda27f6f0d152ec643bc9f882e0f87c72d0749bccddac3ee8307eb1c |
| `commerce-app/src/test/java/com/lrj/commerce/app/PersistedCommerceTest.java` | 796bac88b184825e8ec46b629666c5ee84c05e70d670e6ca5e109949545cfe46 |
| `marketing-automation/src/main/java/com/lrj/commerce/journey/api/JourneyApi.java` | c2a6bdda947736bff08cf48953be13df3a66c01083e6f7b1925a33977384daa8 |
| `marketing-automation/src/main/java/com/lrj/commerce/journey/application/JourneyActions.java` | 1147202be0e3fd5c55fe2a72b642392c1e0d0ee0b440bcd57daa2bf00aea8d27 |
| `marketing-automation/src/main/java/com/lrj/commerce/journey/application/JourneyService.java` | abc7998235fcf8b7ffa1aa1bd28fc040afb65a2576b20a62c44e00537b1f8439 |
| `marketing-automation/src/main/java/com/lrj/commerce/journey/domain/JourneyGraph.java` | e15e91bd60cb8a45a0e33f149cf5bb43c230901a95d4225e7b8acddb946ecad8 |
| `marketing-automation/src/main/java/com/lrj/commerce/journey/infrastructure/persistence/JourneyMapper.java` | d8a665843b95de5d88558bca48a01de126de770f1c5a23089cf92b38f8128a1b |
| `marketing-automation/src/main/resources/mappers/journey/JourneyMapper.xml` | eb686658b051617328daa4c973fa8decc32832f69901fc81e7687cf136cb5da3 |

## 允许交付路径

- `CODEX_PROGRESS.md`
- `commerce-app/src/main/java/com/lrj/commerce/app/configuration/security/SecurityConfiguration.java`
- `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/journey/JourneyController.java`
- `commerce-app/src/main/resources/db/migration/V44__journey_step_execution_history.sql`
- `commerce-app/src/main/resources/db/migration/V45__journey_due_discovery_indexes.sql`
- `commerce-app/src/test/java/com/lrj/commerce/app/JourneyGraphTest.java`
- `commerce-app/src/test/java/com/lrj/commerce/app/JourneyRecoveryTest.java`
- `commerce-app/src/test/java/com/lrj/commerce/app/PersistedCommerceTest.java`
- `docs/delivery/phase8-marketing-journey/BACKEND_ARCHITECTURE.md`
- `docs/delivery/phase8-marketing-journey/CONTRACTS.md`
- `docs/delivery/phase8-marketing-journey/DELIVERY_PLAN.md`
- `docs/delivery/phase8-marketing-journey/DELIVERY_STATUS.md`
- `docs/delivery/phase8-marketing-journey/IMPLEMENTATION_SLICES.md`
- `docs/delivery/phase8-marketing-journey/P8-S1_TEST_RESULT.md`
- `docs/delivery/phase8-marketing-journey/P8-S2_TEST_RESULT.md`
- `docs/delivery/phase8-marketing-journey/P8-S3_TEST_RESULT.md`
- `docs/delivery/phase8-marketing-journey/P8-S4_TEST_RESULT.md`
- `docs/delivery/phase8-marketing-journey/P8-S5_TEST_RESULT.md`
- `docs/delivery/phase8-marketing-journey/QA_RESULT.md`
- `docs/delivery/phase8-marketing-journey/REVIEW.md`
- `docs/doc-map.md`
- `docs/evidence/phase8-marketing-journey/00-baseline.md`
- `docs/evidence/phase8-marketing-journey/01-current-journey-inventory.md`
- `docs/evidence/phase8-marketing-journey/02-journey-domain-model.md`
- `docs/evidence/phase8-marketing-journey/03-state-machines.md`
- `docs/evidence/phase8-marketing-journey/04-graph-validation.md`
- `docs/evidence/phase8-marketing-journey/05-versioning-publication.md`
- `docs/evidence/phase8-marketing-journey/06-runtime-model.md`
- `docs/evidence/phase8-marketing-journey/07-wait-semantics.md`
- `docs/evidence/phase8-marketing-journey/08-condition-branch.md`
- `docs/evidence/phase8-marketing-journey/09-action-integration.md`
- `docs/evidence/phase8-marketing-journey/10-idempotency-recovery.md`
- `docs/evidence/phase8-marketing-journey/11-crash-restart.md`
- `docs/evidence/phase8-marketing-journey/12-multi-instance.md`
- `docs/evidence/phase8-marketing-journey/13-scale-backlog.md`
- `docs/evidence/phase8-marketing-journey/14-security.md`
- `docs/evidence/phase8-marketing-journey/15-observability-runbook.md`
- `docs/evidence/phase8-marketing-journey/16-regression.md`
- `docs/evidence/phase8-marketing-journey/MATRICES.md`
- `docs/evidence/phase8-marketing-journey/PHASE8_REPORT.md`
- `docs/evidence/phase8-marketing-journey/results/due-query-plan.md`
- `docs/evidence/phase8-marketing-journey/results/query-before.md`
- `docs/evidence/phase8-marketing-journey/results/scale-results.md`
- `docs/evidence/phase8-marketing-journey/results/scale.csv`
- `docs/evidence/phase8-marketing-journey/results/tenant-discovery-plan.md`
- `docs/evidence/phase8-marketing-journey/scripts/README.md`
- `docs/evidence/phase8-marketing-journey/scripts/backlog_scale.py`
- `docs/evidence/phase8-marketing-journey/scripts/browser-affected.sh`
- `docs/evidence/phase8-marketing-journey/scripts/mutation_checks.py`
- `docs/evidence/phase8-marketing-journey/scripts/probe_support.py`
- `docs/evidence/phase8-marketing-journey/scripts/process_recovery.py`
- `docs/evidence/phase8-marketing-journey/scripts/rolling_compatibility.py`
- `docs/evidence/phase8-marketing-journey/scripts/warm_recheck.py`
- `marketing-automation/src/main/java/com/lrj/commerce/journey/api/JourneyApi.java`
- `marketing-automation/src/main/java/com/lrj/commerce/journey/application/JourneyActions.java`
- `marketing-automation/src/main/java/com/lrj/commerce/journey/application/JourneyService.java`
- `marketing-automation/src/main/java/com/lrj/commerce/journey/domain/JourneyGraph.java`
- `marketing-automation/src/main/java/com/lrj/commerce/journey/infrastructure/persistence/JourneyMapper.java`
- `marketing-automation/src/main/resources/mappers/journey/JourneyMapper.xml`

另独立fix/ci-browser-contracts bdc2af7含frontend/tests/catalog-merchandising.spec.ts、coupon-deliveries.spec.ts、member-behavior.spec.ts、member-cycles.spec.ts与自身DELIVERY_RESULT。没有前端产品改造。

初始ROUTER_CONTEXT/ROUTE_DECISION是启动历史（仅设计路由），保留原记录；后续正式架构/契约/切片、P8-S1–S5_TEST_RESULT与授权来自用户确认及最新AGENTS，不能把启动历史误解为当前未实施。当前恢复状态见CODEX_PROGRESS和DELIVERY_STATUS。
