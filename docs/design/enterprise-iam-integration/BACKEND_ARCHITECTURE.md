# 企业 IAM 接入目标架构

- Artifact：BACKEND_ARCHITECTURE；Owner：backend-architecture-design。
- 状态：PROPOSED；范围：用户体系、权限治理与应用接入。已有 OA 决策、auth 协议、商城过滤保持兼容。
- 输入：[整体方案](README.md)、[证据](EVIDENCE_INDEX.md)。物理迁移当前仅有边界准备计划，不宣称架构批准或部署完成。

## 1. 不变量

1. 认证成功只确认身份，企业成员关系、应用准入、动作、数据范围与时间条件均需成立。
2. 企业、应用、环境有明确作用域；角色名字相同不共享权限。平台运维权限不自动包含企业业务权限。
3. 每个授权保留动作与数据范围的关联；不能产生权限与范围的笛卡尔积。
4. 身份、成员、应用或授权停用后，新操作必须按撤权策略被阻断；凭据未过期不能绕过撤权。
5. 角色、授权与身份状态有唯一写入权威；缓存、关系投影与业务数据副本不是新的独立写权威。
6. 列表与总数在分页前过滤；详情、写入、导出与任务使用同一语义；范围不支持则拒绝。
7. 外部人员无需员工档案；普通消费者无需手工分配企业运营角色；机器不得借用负责人权限。
8. 原 subject、actor、member、资源标识与幂等回执可共存；身份映射或认证迁移不重建订单和会员。
9. 回退保留授权撤销、停用与新的安全约束，不允许恢复陈旧的宽权限。

## 2. 模块、职责和依赖

| 逻辑模块 | 目标职责 | 初期承接与边界 |
|---|---|---|
| AuthenticationAdapter | Casdoor OIDC、可信 issuer 与客户端、身份绑定 | 保留已有 JWT/认证边界；商城新增适配 |
| IdentityDirectory | 人类／机器主体、外部绑定、成员关系 | OA IAM 增量演进；员工来源经 OrgDirectoryPort 接入 |
| ApplicationRegistry | 应用、环境、客户端、负责人、协议能力与准入 | OA 治理模块中新增；不能用非人身份类型 APPLICATION 代替应用登记 |
| PermissionCatalog | 应用动作、风险、页面／接口映射、目录版本 | 应用提供 manifest，平台验证归属后发布 |
| RoleAndEntitlement | 角色、授权条款、可授予上限、来源、时间窗、职责冲突 | OA IAM 治理能力复用；新增 app/environment/member 作用域 |
| AccessGovernance | 申请、审批、临时权限、外部邀请、复核、收权进度 | 复用 OA 审批能力；待办委托与权限委托继续分开 |
| AuthorizationFacade | 组合身份准入、动作、范围、对象关系、字段与新鲜度 | 明确模块端口；在一期兼容原引擎，不强制把所有判权改成远程调用 |
| RbacScopeAdapter | 角色／条件决策与动作对应的范围 | 保留 OA 引擎，验证扩展后通过统一协议输出；原 OA 数据范围适配器继续负责 PG 查询 |
| RelationshipAdapter | 对象共享与对象访问关系 | 复用 auth-platform 的 HTTP/JSON SpiceDB 适配，保留各工作区模型 |
| IntegrationSdk | 可信上下文、动作检查、范围规范、对象检查、版本／失效、审计关联 | 新增兼容模块；只依赖公开协议，不依赖 OA 组织、Mapper 或实体 |
| ApplicationPolicyAdapter | 将范围映射到业务资源与查询、写事务、异步任务 | 每应用保有；商城 MySQL 与 OA PostgreSQL 分开实现 |

目标依赖方向：业务应用 → 接入协议／SDK → 授权端口 → 对应引擎适配器；身份目录 → 员工来源端口 → OA 组织。权限目录和治理核心不导入 OA 考勤、公文或商城订单实体。

当前 `oa-iam` 依赖 `oa-org`、`oa-security`、`oa-common`、`oa-protocol`，SDK 不能直接复制整个模块。先建立公开端口和兼容实现，再处理模块抽取。auth 的 `AuthzEngine` 九项既有操作和 grpc-free HTTP 约束保持；新增统一协议不能删除旧操作或改变旧响应含义。

OA 的 PermissionSnapshot 已有按 permission／role 保存数据范围和 ABAC 分支的能力。目标应先复用其正确语义，扩展 app/environment、外部成员和门店／合作方范围，再通过统一协议输出；不把这些能力重写成另一套平行引擎。旧 OA 对 schema 合并的警告与 auth 当前多工作区部署需逐实例核实，不能按一份旧说明盲目合并所有 schema。

## 3. 用户和授权的概念数据模型

以下为拟议逻辑模型，非已存在表或本轮执行的 DDL。最终增量表名、类型、索引和约束须在 IAM-02/04/05 切片中冻结；建表必须含中文表／字段注释，使用新迁移，不修改已执行迁移。

| 概念 | 关键字段 | 完整性与所有权 |
|---|---|---|
| Identity | identityId、kind、globalStatus、version | 稳定主体 ID；保留现有 USER 与 NHI 类型；人类员工关系不再是创建人类身份的唯一方式 |
| IdentityBinding | issuer、subject、identityId、source、verification | `(issuer, subject)` 唯一；受控关联，不以邮箱自动合并；跨客户端 subject 映射需验证 |
| TenantMembership | tenantId、identityId、affiliation、sourceRef、status、validFrom/To、sponsor | 同人可有多个关系；生命周期按关系处理；雇佣权威仍是 OA/HR |
| PartnerOrganization | tenantId、partnerOrgId、status、owner、contractRef | 合作方不进入员工部门树；资质资料和合同业务可以由已有业务域提供引用 |
| Application | appId、owner、status、supportedResourceTypes、contractVersion | 唯一稳定 appId；应用删除／停用不抹除历史授权与审计 |
| ApplicationInstallation | tenantId、appId、environment、status、clientRefs | 企业对应用的启用与准入上限；多个客户端映射一个业务应用 |
| PermissionDefinition | appId、permissionCode、resourceType、action、risk、version | 权限由应用所有者定义；增加敏感动作需审批，不默默扩展既有角色 |
| RoleDefinition | tenantId、appId、roleCode、roleVersion、permissionRefs | 角色版本与权限目录版本可审计；数据范围不靠复制角色编码表示 |
| RoleAssignment | tenantId、appId、environment、subjectRef、roleVersion、scopeBindings、conditions、window、source、version | 授权条款保存动作／资源／范围关联；已批准角色版本变更可控制地迁移，不静默自动放大 |
| DelegatedAdminPolicy | operator、managedMembers、grantableRoles、maxScopes、maxWindow、constraints | 管理上限独立于业务授权；授予必须是上限的子集，禁止自批和越权转授 |
| ApplicationMembership | tenantId、appId、membershipId、status | 应用准入独立于授权动作；适用于员工／合作方；消费者基础准入可由受控策略计算 |
| ResourceScopeBinding | appId、resourceType、scopeType、typedValues、ownershipVersion | 门店等 ID 由业务端验证；大集合使用受控范围投影／关联查询，不无限列表 |
| AuthorizationChange | changeId、revision、state、affectedApps、receipts、reason | 治理事务提交与下游应用进度分别显示；完成判据为受影响执行路径已达到要求 |
| Invite / AccessRequest / Review | 目标成员、申请条款、审批引用、有效期、状态、幂等键 | 令牌只存哈希；单次接受；审批结果不得被重复执行扩大范围 |

企业／应用／环境作用域必须进入业务唯一约束、查询条件、缓存键、审计和幂等键。UUID 与旧数值 ID 通过类型化引用和映射共存，不允许强制把各系统现有主键转换成同一类型。

## 4. 数据权威与写入口

| 数据类别 | 一期权威与写入口 | 目标治理／消费方式 |
|---|---|---|
| 登录凭据与认证绑定的验证 | Casdoor，平台认证适配器 | 不在业务库复制密码；IdentityBinding 保存经验证的对应关系 |
| 员工、组织、岗位 | OA org／未来企业 HR 的明确权威 | IdentityDirectory 是身份投影与成员关系视图，不直接改员工表 |
| 外部人员准入、担保、邀请 | IAM 治理模块 | 单一服务写入口；与合作方或合同业务通过引用／端口协作 |
| 角色、动作、组织授权、审批与治理条款 | OA IAM 现有 PostgreSQL 及增量模型 | 逐应用确认切换权威；快照仅为读模型 |
| 对象授权关系 | 对应 auth 工作区 SpiceDB，走其管理／关系写端口 | 一期保留此权威，不在 OA 数据库建立可独立修改的第二份对象 ACL |
| 当前商城商品经营授权 | 商城 `store_operator_grant` 的公开服务入口 | 在该条款切换前保持权威；目标管理平台通过服务适配，不跨库裸写；切换后旧入口只做同一新权威的兼容门面 |
| 订单、门店、商家、会员及资源归属 | 商城各业务模块，MySQL | IAM 只持有引用；人员与门店、合作方与资源的映射需业务端验证 |
| OA 与 auth 缓存、业务范围投影 | 非权威读模型 | 有版本、有效期、失效与重建；禁止在缓存中直接修改授权 |
| 审计 | 各权威写操作的事务内记录＋跨应用关联 | 业务决策日志不替代授权变更审计；持久化与保留目标逐项确认 |

角色授权和对象 ACL 可以有各自的权威模块，但同一条权限事实不能同时被两处独立修改。初期现状通过适配入口治理，不做无恢复的同步双写；后续改变权威时必须定义单写阶段、增量桥、对账和退出条件。

## 5. 授权决策及范围组合

对企业应用的一次请求，依次检查可信身份 → 企业及成员关系有效 → 应用安装／准入有效 → 动作目录存在 → 授权条款有效 → 与该动作关联的范围匹配 → 必要对象关系／字段／风险约束 → 决策新鲜度满足要求。

表达式：

```text
allow = trustedIdentity AND activeContext AND appAdmitted
        AND noApplicableMandatoryDeny
        AND EXISTS(validGrantClause:
              clause.contains(action, resourceType)
              AND clause.scope.matches(resource)
              AND clause.conditions.hold)
        AND requiredObjectRelationHolds
        AND requiredFreshnessHolds
```

消费者基础策略替代人工 grant，但同样受身份状态、会员绑定、租户与本人资源约束。必须的范围和对象条件采用 AND；一个引擎允许不能覆盖另一个必须条件的拒绝。角色多个正向条款可对同一动作取并集，生命周期、企业禁用和授权管理上限作为强制限制。

列表的语义是先取得该动作的范围，再在查询中限制 tenant 和资源集合。`SELF` 在不同资源上有不同的绑定：OA user_id、商城 member_id 不能混用。无法实现安全的对象集合查询时，先不开放该列表；已有 checkBulk 或 lookupResources 不代表支持无限集合和正确分页。

## 6. 事务、一致性与撤权

同一 OA IAM 治理库内的成员／授权变更、revision 与变更审计使用本地事务。涉及独立 auth、商城或客户端快照的副作用，在主事务内记录可靠意图并持久化推进状态；按真实消费者复用已有 outbox／恢复工具，不默认新增 broker。

新增授权在下游尚未准备好时保持 PENDING／APPLYING，不提前展示为全部生效。撤权先登记权威禁用或新 revision，再推进下游；请求端必须遵守最新状态／租约策略。权限变化事件含 app、tenant、environment、主体、revision、changeId；仅接受较新版本，去重、乱序和重放可恢复。

现有 OA epoch 刷新失败会在已有缓存时沿用旧值；现有 auth 服务的 ZedToken 水位是单实例内存。二者都不足以直接证明跨平台严格撤权。在 IAM-08 中建立按风险分类的 freshness 规则：高风险写入与明文敏感读需验证权威版本；低风险缓存必须有明确最大有效租约。无法证明状态或新鲜度时拒绝／返回依赖不可用，不能回退成 ADMIN 或忽略范围。

SpiceDB 检查按具体工作区携带可验证的写入水位；不可把 ZedToken 当会话令牌或不同实例间可比较的数字。写后读／撤权传播须结合适当的一致性模式。[SpiceDB 一致性](https://authzed.com/docs/spicedb/concepts/consistency)

收权完成回执表达已达到的适用范围与版本，不承诺撤销此前已提交的资金、库存或审批效果。业务长任务在受理与每次新的受保护副作用前重检；支付对账、已经发生订单效果的补偿等系统责任由独立服务主体继续执行，不能因员工离职把必要恢复任务永久丢弃。

## 7. 技术选择与复杂度预算

| 决策 | 建议 | 理由、备选与代价 | 阶段 |
|---|---|---|---|
| 认证 | REUSE_EXISTING：Casdoor | 已有 OIDC 与客户端开通能力；当前不换 IdP；新增客户端验证安装版本支持的流程与 claims | P1/P3 |
| 身份与治理持久化 | REUSE_EXISTING：OA PostgreSQL | 角色／审批已有；新增模型增量迁移；直接复制到第三库会产生多权威 | P1/P2 |
| 对象授权 | REUSE_EXISTING：auth HTTP SpiceDB 适配与工作区 | 既有对象访问链路与消费者保留；不把它改为 OA 全部 API 的主判权引擎 | P2 |
| SDK 与统一协议 | BUILD 增量兼容模块 | 现有 SDK 分别不能完整覆盖应用角色＋数据范围；新模块隔离 OA 内部依赖；保留原 SDK API | P2 |
| 范围查询 | BUILD 应用适配，复用 Mapper | PG org_path 与 MySQL store/member 语义不同；平台只发类型化范围，不发任意 SQL | P3/P5 |
| 可靠变更分发 | REUSE_EXISTING 已有 outbox／持久化作业，确有跨进程副作用才启用 | 处理提交后断连与重试；不无理由新增 Kafka/RabbitMQ；授权热路径不等消息完成 | P2 |
| 进程、网关、配置、缓存 | 初期沿用部署与配置，按现有指标扩展 | 单进程合并、全远程 PDP、额外配置中心没有当前测量依据；现有缓存先补撤权证明 | P0–P5 |
| 仓库／Runtime 合并 | DEFER 到 P6 | 需要发布耦合、容量、故障域、依赖、回退和所有权证据；域边界不自动决定部署边界 | P6 可选 |

不新增数据库引擎、搜索平台、工作流引擎或质量平台。不升级依赖；兼容开发使用各仓锁定的 JDK21/Spring Boot 与构建规范。新增 API／事件族与 SDK 主版本的兼容性需要真实消费方验证。

## 8. 当前事实、建议与风险分离

- FACT：OA 的人类身份受员工投影限制；IAM 依赖组织模块且已有按权限／角色的数据范围；当前 Check 人类 API 权限点为主；auth 提供对象判权与关系操作；商城 Actor 是固定角色，已有租户／本人／商品门店限制。来源 F01–F15。
- PROPOSED D01：统一 IAM 治理产品与协议，保留办公与商城业务所有权；不把一期物理合并作为接入条件。
- PROPOSED D02：外部人类由成员关系模型接入；保留 Casdoor subject 和旧业务 ID。
- PROPOSED D03：企业、应用、环境分开；角色版本＋动作范围条款；治理上限独立。
- PROPOSED D04：先单写权威与兼容适配，再影子和受控切换；禁止旧／新判权 OR 放行。
- PROPOSED D05：范围平台化语义、业务本地执行；严格拒绝不支持的范围与失效的授权。
- ASSUMPTION：一期以企业内部多个应用为主，外部包含合作方与商城消费者；最终隔离与政策以 Q01–Q07 为准。
- RISK：身份错误合并、权限串应用、范围串动作、旧缓存继续允许、跨权威双写、回退复活授权、外部成员漏收权、任务处理错误。见 [迁移风险](MIGRATION_AND_VALIDATION.md)。

容量、授权时延、撤权 SLA、最大范围集合、峰值人数与 RTO/RPO 当前 UNKNOWN；必须在对应门禁前测量／确认，不能根据模块数或注释中的性能声明认定已经达标。
