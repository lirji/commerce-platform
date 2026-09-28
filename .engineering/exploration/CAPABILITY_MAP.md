# 当前能力地图：阶段 8 后复核

- generated_at：2026-09-27（America/Los_Angeles）
- workspace：`/Users/liruijun/personal/LLM/commerce-platform`
- baseline：`7dda31ed017617f3701480f8a2d1620656c843cc`，分析开始时工作树干净。
- permission_followup：`afc5e940c1dd988759880ad9f03106e68170ad0e`；专项复核页面RBAC和接口数据范围。
- protocol：`engineering-baseline/v1`、`skill-contract/v1`、`capability-exploration-report/v1`
- skill：实际读取 `~/.claude/skills/project-capability-exploration/SKILL.md`、references 与 output-schema；这些 Claude 入口链接至 `~/.cursor/skills` 共享技能。复用 Claude 中 discovery、deep-analysis、architecture-reviewer 的扫描方法，不声称调用了 Claude 模型。
- scope：源码、HTTP/API、41 份 Mapper XML、45 份迁移、测试源码、前端、CI、阶段 2–8 证据。19 个 Maven 模块、205 个主 Java 文件、54 个测试 Java 文件为静态清单数量，不等于逐文件完整审计。
- confidence：直接源码/配置为 FACT；未发现完整能力及建议为 INFERRED；业务政策为 UNKNOWN；未经本轮实测的运行/容量为 NEEDS_VERIFICATION。源码优先于旧报告。

## Exploration Summary

现在是具有会员经营、商品经营、营销决策、持久旅程和交易闭环的模块化单体，已有可恢复机制明显强于 9 月 23 日基线。最该补的三件事：多项目能力接入边界、自营商城持久购物链路、运营治理与持续运行保障。最不该做的事：没有新业务和容量证据就继续建设通用 BPM/微服务平台。

沿用已有记录的“会员/商品/营销共享中台＋品牌自营商城”定位；第二接入项目和数据共享政策仍未知。外部 IdP、支付、权益、WMS 联调维持后置安排。

## Current Capability Overview

### Repository & Module Map

| 模块 | 真实职责与关键入口 | 证据 |
|---|---|---|
| shared-kernel | 金额、标识、领域异常 | E01 |
| marketing / order | 纯规则决策、确定性活动选择；订单合法状态迁移 | E02 |
| platform-runtime | 身份、事务命令/审计、Outbox/Inbox、消费隔离、公平轮转、恢复/重放/保留 | E03/E04/E16/E18 |
| member | 档案状态、成长/标签、周期等级、积分、行为事实、逐项恢复 | E05–E07 |
| merchant / store | 商家/门店主数据，商品运营范围授权 | E08 |
| catalog | 商品/SKU、类目/规格/图文/条码、检索、定时经营、渠道价 | E09–E11 |
| marketing-runtime | 活动/人群/规则资产/动态分群、预算、营销执行 | E12/E13 |
| benefit | 券钱包、额度预留、权益台账/补偿、等级礼包、积分兑换 | E14/E15 |
| trade / inventory / order-runtime | 可信报价与成交快照、库存额度、订单及到期恢复 | E19/E20 |
| payment / fulfillment / aftersales | 持久支付退款沙箱、未知查单、履约、部分退货退款 | E21/E22 |
| marketing-automation | 持久旅程/生命周期扫描、批量发券、站内通知、低代码、效果投影 | E23–E27 |
| commerce-app / architecture-tests | HTTP/安全/装配、共享有界调度、指标告警；编译依赖约束 | E17/E28/E29/E32 |
| frontend（非 Maven 模块） | 管理台、商品经营、购物/订单、会员运营、营销工作台 | E30/E31 |

运行是一个 Spring Boot 应用和 MySQL，不是 19 个独立服务；未发现 Redis、独立 Broker、ES、配置中心或 LLM 的业务调用。

### 本轮撤销的旧缺口

| 旧结论 | 当前复核 | 新边界 |
|---|---|---|
| 会员不能冻结/注销 | 已有 ACTIVE/FROZEN/CLOSED、版本校验和历史，E05 | 注销不等于敏感数据删除 |
| 没有等级成长/周期/积分 | 已有成长、保级考核、周期权益、积分来源批次/到期/退款/兑换/订单抵扣，E06/E15 | 跨项目共享和政策治理仍不完整 |
| 没有标签、行为、动态人群 | 已有标签与行为事实、持久分群刷新、快照完整发布，E07/E13 | 大名单导入和真实大租户持续容量未认证 |
| 没有类目规格、图文、渠道价格或批量经营 | 已有这些能力，E09–E11 | 图片 URL 不等于文件资产中心；商品仍属于店铺 |
| 后台任务全串行、一个坏事件阻塞全部消费者 | 已有独立车道、有界线程池、消费者事务隔离和逐项恢复，E04/E17/E18 | 仍共享进程、连接池和 MySQL |
| 没有指标、告警、保留期机制 | 已有指标、告警代码、受控清理机制，E16/E28/E29 | 告警默认写日志；保留政策未定，清理默认关闭 |
| Journey 没有多进程/崩溃证据 | 阶段 8 已有实际 kill/restart、两个 JVM、旧新版本和规模证据，E24 | 历史 Facts、生产 SLO、Journey 保留期仍缺 |
| 浏览器仍有四项已知契约断言失败 | 阶段 8 记录全 24 项通过；基线 main 的 CI 也已核实 success，E33 | 本轮没有重跑业务测试 |

9 月 25 日 `.project-analysis/` 和 9 月 23 日架构审查也是历史输入，其中串行调度、无观测、无恢复等结论不能覆盖当前源码。本轮只再生本目录五份分析产物。

## Business

| 能力 | maturity | 真实性/证据 | 剩余边界 |
|---|---|---|---|
| 会员基础与生命周期 | PARTIAL | USED；E05/E07，FACT | 单一主体绑定；无接入应用会员映射、验证联系方式/合并政策 |
| 成长、周期等级、积分 | MATURE（现有本地政策内） | CRITICAL；E06/E15，FACT | 不据此宣称储值、付费会员或跨应用资产共享已实现 |
| 商品经营 | PARTIAL | USED；E09–E11，FACT | 主档随店铺；媒体为 URL；单经营任务最多 100 SKU |
| 活动/规则/人群/预算 | PARTIAL | CRITICAL；E12/E13/E14，FACT | BEST_OF 单活动＋既有券；非任意叠加/多类型礼包 |
| Journey 与生命周期营销 | MATURE（有界本地模型） | CRITICAL；E23/E24，FACT | 32 节点 DAG、单路径；站内通知；无历史全 Facts |
| 报价、订单、退货退款 | MATURE（单店 CNY/沙箱范围） | CRITICAL；E19–E22，FACT | 无持久购物车、地址簿、配送费用；外部资金/物流后置 |
| 营销经营分析 | PARTIAL | USED；E26，FACT | 描述性队列与净收款，不是增量因果 ROI 或全成本利润 |

## Platform

已有 Commands、事件处理、WorkLanes/TenantRotation、Recovery/ReplayGate、规则 API、JourneyActions 和 Ops 白名单组件，多个业务已消费（E03/E04/E17/E18/E23/E27）。公共运行机制为 MATURE（当前本地范围）。

能力中台对外复用为 PARTIAL：有 Java API 和登录态 HTTP，但 Actor 没有接入应用身份；会员/商品没有外部映射；成长/营销/效果仍消费本地订单事实。开放凭据、合作方交付记录、第二独立应用验收在仓库中未发现完整闭环（G01/G02，INFERRED）。

## Engineering

- MATURE（已有验证范围）：Flyway V1–V45、真实 MySQL 集成验证、jdeps 边界检查、Chromium API 驱动验收、隔离测试租户与种子、构建 UI 入 jar、CI，E32/E33。
- PARTIAL：依赖供应链检查。CI 可见 npm audit；未发现等价的后端完整依赖扫描、SBOM 或制品签名门禁，E33。未据此推断存在某个具体漏洞。
- PARTIAL：发布证据。阶段 7/8 有旧新二进制兼容与激活点限制；部署仍是本地 Compose，不等于生产灰度/灾备验收，E24/E34。

## Reliability

事务命令、数据库唯一/CHECK/条件更新、固定快照、UNKNOWN 保守处理、Outbox/Inbox、退避/隔离、原节点恢复已经 USED/CRITICAL（E03/E04/E14/E18/E19/E21/E23）。有限模型下成熟度 MATURE。

生产可用性为 PARTIAL：只有单数据源/单应用部署声明；真实备份恢复、数据库切换、网络分区和外部副作用故障尚未认证（E34/E35）。真实退款拒绝的终态与额度处理在当前仅 UNKNOWN/SUCCEEDED 模型之外（E21；G13）。

## Observability

PARTIAL。已有低基数事件/车道/重放/保留指标、平台运维聚合 API、调度延迟/隔离/年龄告警及手册（E28/E29）。默认 OperationalAlertPublisher 仅 WARN 日志；application.yml 仅暴露 health，未发现远程指标导出/告警送达验收。缺的是持续采集、值班送达、清除/升级以及真实 SLO，不是“没有监控”。

## Data

PARTIAL。权威关系库、不可变业务版本、资产引用、可重建效果投影、索引与迁移已具备（E03/E09/E12/E19/E26）。终态事件/命令清理机制默认关闭，Journey 步骤、行为和敏感资料没有完整生命周期政策；CLOSED 只改变状态（E05/E16/E23）。

## Security

PARTIAL。摘要 Bearer、租户/本人检查、默认拒绝 HTTP 清单、OPERATOR 商品门店范围、独立 PLATFORM_OPERATOR、地址 AES-GCM/AAD 已具备（E08/E29/E30/E35）。可配置页面/按钮/接口RBAC（G05）与通用业务数据范围授权（G16）尚未形成闭环，不能只归为营销职责分离。调用应用权限、密钥轮换和真实 IdP 后置。

| 权限层次 | 当前源码事实 | 成熟度与剩余边界 | 证据 |
|---|---|---|---|
| 页面/按钮 | 菜单写在App，按ADMIN/MEMBER/OPERATOR分支；表单按admin/沙箱开关展示；runtime-capabilities仅是运行开关 | BASIC；可配置菜单/动作权限、角色授权管理及权限驱动页面/按钮闭环ABSENT | E38 |
| 接口动作 | SecurityConfiguration路径角色清单＋Actor.requireAdmin/require；四种固定角色，Capability固定映射 | PARTIAL；可配置角色/动作授权与职责分离缺失 | E30/E39 |
| 租户与会员本人 | 从凭据取tenant/actor；会员订单SQL包含tenant＋member，会员列表管理读取按tenant | 已实现基础隔离；不是同租户岗位数据范围体系 | E30/E40 |
| 商品经营资源 | store_operator_grant限定CATALOG及STORE/MERCHANT；目录SQL过滤，经营动作直接查权威授权 | 已实现该业务范围；不是订单/会员/营销通用授权 | E08/E41 |
| 跨业务数据范围 | 会员管理按tenant，订单adminList传member=null，adminRead按tenant；未见部门/负责人/可配置门店范围闭环 | 通用闭环ABSENT（INFERRED）；列表/详情/汇总/写操作/任务要保持一致，具体维度待业务确认 | E40 |

现状事实不等于“接口可以任意访问”。同租户ADMIN可读本租户管理数据符合现行固定角色模型；若要授予受限岗位，必须先补G05/G16，不能仅隐藏菜单或接受前端store参数作为权限证明。本轮没有复现越权漏洞。

## Integration

PARTIAL。支付/退款/WMS/权益有适配端口，但真实身份、资金、仓储尚未验收；无合作方事实摄取和事件可靠交付闭环（E21/E22/E30/E36）。WEB/MINI_APP 是受认证控制的销售渠道，不能当作第三方接入应用。

## AI

ABSENT，FACT（依赖、配置和业务调用扫描）。当前无已确认的模型调用场景；没有必要为“中台”添加 Agent、RAG 或 NL2SQL。以后若选定运营辅助场景，应先解决事实口径、权限和效果评估（X01）。

## Capability Maturity Summary

| dimension | maturity | 评价范围 |
|---|---|---|
| BUSINESS | PARTIAL | 本地会员/营销/交易深度较足，持续购物和共享主数据仍缺 |
| PLATFORM | PARTIAL | 公共运行机制成熟，对外业务复用尚未形成 |
| ENGINEERING | MATURE（本地验证） | 后端供应链及生产发布认证另列缺口 |
| RELIABILITY | PARTIAL | 本地一致性/恢复有证据，生产 HA 和真实渠道仍未认证 |
| OBSERVABILITY | PARTIAL | 有指标/代码，缺持续采集和送达 |
| DATA | PARTIAL | 有治理机制，缺完整政策和生命周期 |
| SECURITY | PARTIAL | 基础角色/租户/本人/商品范围已有；可配置RBAC和通用数据范围缺失，应用/密钥治理待补 |
| INTEGRATION | PARTIAL | 端口已留，真实合作方与渠道闭环后置 |
| AI | ABSENT | 当前不建议启动 |

全部候选见 [缺口](CAPABILITY_GAPS.md)、[机会](OPPORTUNITIES.md)、[路线](EVOLUTION_ROADMAP.md)。证据见 [索引](EVIDENCE_INDEX.md)。MATURE 不表示生产认证或所有企业场景均已支持。
