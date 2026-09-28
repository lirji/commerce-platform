# 用户体系与权限接入计划证据索引

- generated_at：2026-09-27（会话本地日期）；task：IAM-PLAN-20260927。
- 范围：三仓定向读取源码、Mapper、当前设计、进度与 Git；未全仓重扫或运行业务／压测。
- commerce 基线：`f170e317f12006d8559ac41d1858b00c42998890`。
- auth 基线：`c07741a`，读取时工作树干净；OA 基线：`f07c97893238cd97bab38a140b5f6946184d4459`。
- OA 读取时已有 `CODEX_PROGRESS.md`、治理 `PROGRESS_STATE.md` 修改及 `DEPLOYMENT_RESULT.md`、`tmp/` 未跟踪；全部保留，不纳入本轮交付。
- commerce 原工作区在本轮进行中出现并行的 `CODEX_PROGRESS.md`、`docs/design/unified-commerce/FRONTEND_ARCHITECTURE.md` 修改与 `docs/design/frontend-visual-refresh/` 未跟踪；保留原样。本计划迁入 `.local/enterprise-iam-plan-worktree`，交付分支 `feat/enterprise-iam-plan-delivery`；原工作区的当前进度文件不被覆盖或提交。
- 证据定位以“仓库＋相对路径＋类／方法／标题”表达，避免部署机器不同导致绝对路径失效。具体版本范围优先查 Git 与本索引，不把文档目标当运行事实。

## 1. 当前事实

| ID | 仓库与证据位置 | 支持的结论 | 证据类型与限制 |
|---|---|---|---|
| F01 | OA `docs/design/identity-authz-governance/BACKEND_ARCHITECTURE.md` §4–8；`DECISION_RECORD.md` D-GOV-002/004/005/010 | IAM 治理在 OA；Casdoor 认证，OA 引擎授权；旧 human subject 与审批复用为兼容约束 | FACT：当前设计与源码交叉读；不是未来平台批准记录 |
| F02 | OA `oa-iam/.../application/IdentityService.java` create；`domain/IdentityType.java` | USER 手工创建拒绝，只能员工投影；APPLICATION 枚举为非人身份，不是业务应用目录 | FACT：源码；独立外部人员入口当前未闭环 |
| F03 | OA `oa-iam/pom.xml` dependencies；`infrastructure/mapper/GrantReferenceMapper.java` activeUser；`application/GrantService.java` requireSubject | IAM 依赖 OA 组织；人类 grant 校验依赖有效员工；直接复制 IAM 不能得到通用 SDK | FACT：源码／依赖 |
| F04 | OA `application/AuthzCheckService.java` decide/canCheck；`api/dto/AuthzDtos.java` | 人类 Check 主要针对 API 权限点，非人类一般默认拒绝；自己或经授权主体检查；请求没有统一 app namespace | FACT：源码；不能当成完整商城对象／数据范围授权接口 |
| F05 | OA `infrastructure/datascope/OaDataPermissionHandler.java` getSqlSegment/buildCondition；`oa-security` DataScope | 受管表、别名、NONE、SELF 与 org_path 的 PG 范围过滤已有；非所有 SQL 访问方式自动覆盖 | FACT：源码；不是 MySQL 通用过滤器 |
| F06 | OA `infrastructure/cache/PermissionEngine.java` epoch/effectiveEpoch（218–252）；GrantService 变更与 epoch | epoch 读取失败且已有缓存时沿旧值；本地失效／快照机制不能单凭注释证明跨平台严格撤权 | FACT：分支代码；故障下的实际窗口本轮未量测 |
| F07 | commerce `platform-runtime/.../api/identity/Actor.java`；`commerce-app/.../configuration/security/SecurityConfiguration.java`；`CredentialMapper.xml` | 固定 ADMIN/MEMBER/OPERATOR/PLATFORM_OPERATOR、固定能力与角色路径、哈希凭据；外部 IdP 当前未启用 | FACT：源码／SQL；现有默认拒绝与角色边界需兼容 |
| F08 | commerce `store/.../access/application/StoreAccessService.java`；`V17__store_operator_grants.sql` | 商品门店／商家授权存在，角色与租户过滤存在；不能称为完全没有权限 | FACT：源码／迁移；目前非全业务的按动作范围协议 |
| F09 | commerce `order-runtime/.../ordering/order/application/OrderService.java` read/list/adminList/adminRead；`mappers/ordering/OrderMapper.xml`；member `MemberMapper.xml` | 本人会员／订单过滤与管理端租户过滤存在；订单有 store/merchant 字段，可作为新门店读取范围适配基础 | FACT：源码／SQL；客服范围与字段权限需新增设计 |
| F10 | commerce 基线 `f170e31` 的 `frontend/src/app/App.tsx`；`.engineering/exploration/CAPABILITY_GAPS.md` G05/G16 | 基线页面固定角色分支；可配置 RBAC 与通用业务数据范围是独立缺口 | FACT：基线源码及已交付探索；并行视觉改动不被当成新授权功能，实施前重核最新 UI |
| F11 | auth `auth-platform-protocol/.../AuthzEngine.java`；`SubjectRef.java`；`Consistency.java` | check/bulk/lookup/relationships/schema/expand 等九操作；subject 与资源引用；水位一致性独立于登录令牌 | FACT：源码契约，需保留 |
| F12 | auth `auth-platform-sdk/.../CheckAccessAspect.java`；`core` SpiceDbAuthzEngine；`server` ZedTokenWatermark | 对象 AOP 检查与 HTTP/JSON 适配存在；现有注解选择 minimizeLatency 或 fullyConsistent；水位缓存单实例 | FACT：源码／当前说明；没有自动通用 SQL 范围注入 |
| F13 | auth `docs/design/auth-workspaces.md`；`docs/新项目接入指南.md`；`CLAUDE.md` | 多项目 SpiceDB 工作区、组织准入映射、默认 Casdoor 同步路由、schema 整体替换、grpc-free 约束 | FACT：当前文档与协议；不把每项目一 org 永久等同企业模型 |
| F14 | OA `oa-doc/.../infrastructure/authz/SpiceDbKbAuthorizer.java`；两仓 pom／CLAUDE；commerce `.github/workflows/verify.yml` | OA 知识库 SpiceDB 适配为未接通占位；三个仓库构建与运行约束不同；商城有真实 MySQL＋浏览器 CI | FACT：定向文件；不认定 OA 已经全面使用 auth-platform 判权 |
| F15 | OA `oa-iam/.../domain/PermissionSnapshot.java` permissionScope/permissionRoleScope/scopeOfPermissionRoles；`application/PermissionSnapshotBuilder.java` scope 构建 | 已有按权限点／角色保存的组织数据范围及 ABAC 分支，平台演进应复用；跨应用、门店／合作方与独立外部成员仍需扩展 | FACT：源码；不将防范围串联的目标误报为 OA 当前缺少全部范围关联 |

`...` 表示对应完整 Java 包树，可按类名用 rg 定位。证据文件未变更，未读取或输出本地凭据、令牌、client secret、数据库密码。

## 2. 实际读取并使用的 Claude 技能

| 入口 | 本轮用途与执行限制 |
|---|---|
| `~/.claude/skills/backend-architecture-design/SKILL.md`、`tech-selection.md`、`architecture-exploration.md`、其 `_portable/task-guidance.md` | 业务不变量、逻辑模块、权威数据、复用技术、契约需求、失败与恢复；仅设计 |
| `~/.claude/skills/architecture-decomposition-assessment/SKILL.md`、`_protocol/ARCHITECTURE-DECOMPOSITION-v1.md` | 复用上轮方法并定向补证；真实只读 CLI 重新生成可回放源决定，未知成本／运行量不补分 |
| `~/.claude/skills/architecture-evolution-planner/SKILL.md`、`_protocol/ARCHITECTURE-EVOLUTION-v1.md` | 消费源决定，实际 plan＋replay；区分前置治理、迁移就绪与切换许可 |
| `~/.claude/skills/update-progress-docs/SKILL.md` | 根进度和当前设计状态；实施项不伪标 DONE |
| `~/.codex/skills/task-git-delivery/SKILL.md` | 用户 AGENTS 规则 8 授权的当前文档分支／验证／提交／正常 main 发布 |

Claude 入口实际指向共享技能文件；本轮没有调用 Claude 模型，也没有修改全局技能、开发规范或其他仓库。

## 3. 架构演进规划器的实际观察

只读 assessment 使用 G01–G06 原始源码／文档片段与 SHA-256，来自 F01、F02、F03、F07、F11、F13；逐片段现场验证。输入以共同父目录为只读证据根，分析范围仍限这三仓。

| 项目 | 观察值 |
|---|---|
| assessment status | READY_WITH_LIMITATIONS |
| scoped candidate | enterprise-iam：INSUFFICIENT_EVIDENCE |
| extraction score | null，迁移／运行成本及目标运行基线未知，不补成确定分数 |
| assessment input digest | `9eeff46eafe1ddf5d423d2b7fb96716d876d32fcbb607f6648526f3d27254f66` |
| assessment policy digest | `ff7f69c24c24c84fc290fd9eea8057054bb6e6d5cd89431e9c4421fc76f4e60c` |
| migration plan status | REMEDIATION_PLAN |
| strategy | MODULARIZE_FIRST |
| generated step order | baseline → boundary → contracts → ownership → transactions → governance |
| cutover | CUTOVER_BLOCKED；execution_authorized=false |
| replay | PASS，源决定与生成计划确定性回放一致 |
| output scope | 只读 CLI 内联结果；临时输入／完整结果未发布为十类正式迁移产物，不伪造持久化 refs |

所运行的是技能自有 `architecture-decomposition.py assess --grants read_repo --verify-sources`、`architecture-evolution.py plan --grants read_repo --verify-sources` 和 `replay`。临时文件仅用于源决定重放，没有正式数据、schema 或流量动作。

这证明规划门禁没有把未确认信息当作可执行物理迁移。人读建设计划中的后续 IAM 切片是**候选工作**，需要其列出的契约、业务确认与验证；本轮未获得或执行全仓合并、权限迁移或生产切换。

## 4. 官方技术来源与适用点

| 来源 | 核对内容与方案使用位置 |
|---|---|
| [OIDC Core §5.7](https://openid.net/specs/openid-connect-core-1_0.html#ClaimStability) | issuer＋subject 稳定身份键，README §3；不自动按邮箱合并 |
| [OAuth Security BCP RFC9700](https://www.rfc-editor.org/rfc/rfc9700.html) | 授权码／PKCE、精确回调、客户端与令牌安全；契约 §2 |
| [Casdoor 应用概述](https://casdoor.ai/docs/application/overview/) | 各应用登记与复用组织登录；业务权限仍由本方案定义 |
| [SpiceDB Consistency](https://authzed.com/docs/spicedb/concepts/consistency) | 水位及一致性模式；架构 §6；不把单实例内存态当跨节点撤权证明 |
| [OPA 部署与 PDP/PEP](https://www.openpolicyagent.org/docs/deploy) | 管理、判权与应用执行可有不同部署边界；仅方法参考，不选择或引入 OPA |

联网核对为官方主来源；当前组件版本没有升级。Casdoor 安装版的 claims、客户端、刷新／退出支持和各仓运行能力仍须后续实际联调，不用最新网站替代安装版验收。

## 5. 本轮验证边界

本轮只检查文档、源事实引用、依赖 DAG 与进度／范围一致性；具体结果见 [TEST_RESULT](TEST_RESULT.md)。角色策略、数据库完整性、SSO、负向越权、跨实例撤权、压力、恢复和生产均 NOT_RUN。原报告与历史测试保留，不转写成这次计划的业务 PASS。
