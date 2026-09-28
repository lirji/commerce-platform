# Phase 8 实施切片

依赖架构/CONTRACTS与一致性审查PASS_WITH_ASSUMPTIONS，用户已一次确认执行范围。所有slice串行，避免同一JourneyApi/Service/Mapper和迁移链冲突。

| ID | 可观察结果 | needs | owner / allowed paths | 验收 | Runtime | 状态 |
|---|---|---|---|---|---|---|
| P8-S1 | 图稳定校验、发布重检、无副作用预览 | P8.2 | backend：Journey API/domain/service/controller；app相关tests | invalid DAG码；纯条件无动作；WAIT未来边界；原审批回归 | 既有MySQL | DONE |
| P8-S2 | 逐步历史与幂等动作、失败原检查点 | P8-S1 | backend：V44、Journey Mapper/XML/service、显式动作registry、history controller/tests | 成功/等待/条件/actionRef/失败trace；同事务回滚；会员tenant权限；历史固定v1 | 既有MySQL | DONE |
| P8-S3 | 共享车道积压观测及规模/fairness | P8-S2 | backend：Journey Mapper/XML/backlog、既有observability/test、性能脚本 | EXPLAIN、future群、100/1000/10000 due、热点tenant与mixed lanes | 既有调度池/MySQL | DONE |
| P8-S4 | 真支付纵向链、restart/kill/双实例 | P8-S2 | validation：MySQL场景、test-only故障注入、两真实进程脚本 | J1–15，WAIT重启，动作效果后kill/回滚，成功历史不replay；旧/新兼容 | 既有隔离库+owned进程 | DONE |
| P8-S5 | 全回归/运维/证据收口 | P8-S3,P8-S4 | validation/documentation：架构tests、affected浏览器、手册/矩阵/报告 | J1–18/JRN1–20、mutation恢复、hygiene、clean UI jar、全回归 | 现有 | DONE |

所有切片DONE，本地必需验证通过。S1–S3共同变更Service/API/Mapper与schema，作为已共同验证的完整Journey功能单元交付；S4/S5额外可复跑验收为独立证据单元。P8-S4以真实进程与测试事务注入补充，生产源码禁止unsafe fault flags。没有并行Agent计划。每片实现窄验证→独立评审验证→Progress→下一片。
