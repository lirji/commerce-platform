# 统一身份与权限接入契约要求

- 状态：**候选契约要求，尚未冻结或实现**。本文件不替代 OA、auth、commerce 的现有正式 CONTRACTS。
- 用途：IAM-01–08 的契约阶段输入；确认后生成版本化 DTO、API／事件定义与消费方兼容用例。
- 核心约束：[架构](BACKEND_ARCHITECTURE.md)；试点与切换：[执行计划](EXECUTION_PLAN.md)。

## 1. 上下文与信任边界

统一上下文至少包含 `identityId`、`membershipId`、`tenantId`、`appId`、`environment`、`clientId`、`sessionId`、认证来源及凭据版本、traceId。它由认证边界、客户端登记和服务器端准入映射构造。

- `tenantId` 的上下文切换必须通过已验证的成员关系；请求体或自定义头不能直接覆盖上下文。
- 业务调用的 appId/environment 由部署配置与已登记客户端校验确定；跨应用管理请求需要专门授权。
- 平台自查接口只查询当前可信主体。业务服务代人 Check 必须持有绑定该应用的服务身份，并验证被代入人的会话或受控委托凭据；普通调用者不能传任意 principal 查权或冒充。
- 订单、门店、会员、合作方属性从服务器可信业务资料加载；前端自报 `ownerId` 或合作方关系不构成授权事实。
- 判权请求中时间与风险属性不能由普通前端决定；有效期使用服务端时间，时钟偏差预算需确认。
- 全局身份封禁、企业成员退出、应用退出与 credential 吊销分别校验，不能仅验证 JWT 签名和 exp。

## 2. OIDC 与机器认证

每应用／环境登记独立客户端或明确的客户端集合、回调地址允许列表、issuer、受众、退出回调和凭据负责人。浏览器使用授权码＋PKCE S256，绑定 state／nonce 与登录事务，验证回调与一次性代码；不在前端嵌入 client secret，不开启 password grant。

API 使用为该 API／客户端配置所接受的 access token 或经验证的应用会话；ID Token 的登录用途和 API 凭据用途分别定义，不因都长得像 JWT 就通用。结合安装版本验证 Casdoor 的 access token、aud、scope 和刷新／退出行为，未验证前不宣称跨应用 SSO 和全局登出已经完成。

服务端验证可信 issuer、签名与算法、密钥来源、受众、时间条件、客户端映射和会话状态。多受众令牌的客户端／authorized party 约束须明确；不接受开发环境 issuer 或测试客户端作为生产身份。

现有商城内存 Bearer 交互可以兼容过渡；若选 BFF／Cookie 会话，需要单独明确 HttpOnly/Secure/SameSite、CSRF 和会话持久化，不在本方案中默认新增 BFF 进程。凭据轮换、短时访问与刷新失效分别验收。[OAuth 安全建议](https://www.rfc-editor.org/rfc/rfc9700.html)

非人类认证使用登记的凭据／客户端能力，限制调用应用、动作与资源，凭据只存哈希或受控引用，明文仅在创建／轮换时一次性返回。服务账号不使用员工账号密码，Agent 不继承负责人权限。

## 3. API／SDK 能力族

下列是候选能力名，**不是宣称现有 URL 已可调用**。新公开协议使用独立版本或兼容门面；最终路径在 IAM-06 冻结。

| 能力 | 调用者与授权 | 输入／结果约束 | 失败与兼容 |
|---|---|---|---|
| ApplicationRegistration / Installation | 平台／企业应用负责人 | appId、环境、客户端引用、负责人、资源声明；返回版本和状态 | 不能改其他应用客户端；现有工作区单独映射 |
| IdentityBinding / ContextSelection | 已认证人或受控绑定服务 | 经验证认证绑定、成员关系；返回可信上下文 | 同邮箱不自动绑定；旧 subject 映射保持 |
| MeApplications / MePermissions | 当前身份 | 当前上下文可进入应用、动作、菜单、资源范围摘要与版本 | 不返回其他人的权限；摘要不能替代实时判权 |
| PermissionManifestPublish | 应用负责人＋企业变更规则 | 权限清单版本、stable code、资源／动作／风险／页面／API映射、摘要 | 缺失权限默认拒绝；删除权限需消费者退出条件 |
| RoleDefinition / RoleAssignment | 受限授权管理员 | 应用角色版本、主体、动作范围条款、有效期、来源与理由 | 可授予上限、职责冲突、自批检查；管理范围与业务范围分开 |
| CheckAction / CheckObject | 当前人；或获准的应用服务代人 | action、资源类型／ID、可信属性、freshness requirement | 返回 ALLOW/DENY 与内部 reasonCode、policyVersion、有效期限、traceId；依赖故障不是业务 DENY 或 ALLOW |
| ResolveScope | 与 CheckAction 相同，绑定当前应用 | 某一 action/resourceType 的规范化范围条款 | 必须保留动作关联、企业与环境；不返回任意 SQL |
| CheckBulk / LookupResources | 获准服务，声明能力上限 | 有界资源批次／游标、固定上下文和版本 | 保留 auth 旧契约；规范资源对齐、单项错误与上限；不默认支持无限查询 |
| Invite / AcceptInvite | 有管理范围的邀请人；持一次性邀请的认证人 | 接收主体、合作方、应用、候选授权、时间窗、版本 | 原子单次接受；重复／身份不匹配不新增成员或授权 |
| AccessRequest / Decision / Review | 申请人、合法审批人、审计人 | 申请快照、审批依据、角色版本、范围与有效期 | 审批期间目录变更需重新验证；批准不绕过当前可授予上限 |
| Revoke / LifecycleChange | 对应身份／授权负责人 | target、expectedVersion、reason、commandId | 返回 changeId、revision、应用推进状态；不能把已登记等同全部生效 |
| Explain / WhoHasAccess / ChangeReceipts | 被查询者或有作用域的审计人 | 企业、应用、动作、对象；分页与字段限制 | 禁止跨应用枚举身份／权限；只解释可见条款 |

SDK 暴露类型化上下文、动作与对象检查、范围解析、拒绝／不可用转换和 freshness 证明验证。数据库适配留在应用模块：SDK 不带 OA 员工实体，不让 MySQL 应用依赖 PostgreSQL 插件，不拼接用户 SQL。

## 4. 数据范围结构与执行规则

建议结果是一组与请求 action/resourceType 已匹配的授权条款，包含：

```text
ScopeDecision {
  tenantId, appId, environment, action, resourceType,
  policyVersion, membershipVersion, authorizationRevision, expiresAt,
  alternatives: [
    { grantRef, scopeType, typedResourceRefs, conditions, mandatoryRestrictions }
  ],
  mandatoryDeny, freshnessProof
}
```

该结构只说明语义，实际 DTO 字段、签名与传输上限待契约阶段冻结。服务端 SDK 必须核对上下文、版本、请求动作及有效期；前端转发的旧结果或伪造结果不可作为放行依据。

| scopeType | 语义 | 应用实现约束 |
|---|---|---|
| NONE | 该动作无可用数据 | 不执行受保护写入；列表可为空但区分无范围状态 |
| SELF | 当前主体在此资源上的本人绑定 | 按可信 member/user 绑定，不能直接把 identityId 填进所有 self 字段 |
| ORG / ORG_SUBTREE | 指定组织／子树 | 使用服务端组织快照和受控路径；不相信用户提供前缀 |
| RESOURCE_SET | 指定类型的门店／项目等集合 | 小集合有界绑定；大集合经版本化本地投影或关联表过滤，禁止无限 IN |
| BUSINESS_ORG | 商家／合作方及其合法归属资源 | 需应用验证实体、归属与未来新资源是否自动继承；无业务模型就拒绝 |
| OBJECT_RELATION | 对象共享、拥有者、项目关系 | 保留工作区和 tenant 前缀；按资源关系及一致性证明检查 |
| ALL_IN_TENANT | 本企业、当前应用、指定资源类型内全范围 | 必须显式授予，仍保留 tenant/env/action 条件；不表示跨企业或忽略字段权限 |

多条 alternatives 对同一动作取并集，mandatoryRestrictions 与生命周期拒绝整体收窄。任意 AND/OR DSL、动态表／字段、SQL 片段不开放给管理员；只发布批准的结构与允许映射。

查询必须：固定 tenant＋按动作范围条件＋业务过滤＋稳定排序／游标，再分页和 count。Join、子查询、聚合、附件和导出均核实过滤位置。范围未知、类型不支持、表别名错配或映射未执行时拒绝／报不可用，不能返回全量结果。

写入必须：加载真实归属 → 当前权限／版本校验 → 同事务条件更新或锁定 → 检查影响行数 → 审计。归属变更或收权与写入的并发边界须明确，不能用先查后写证明没有竞态。

## 5. 错误、幂等、并发和版本

- 401：缺失／无效认证；403：已确认无准入或权限；404：对象不存在或按应用防枚举策略不可见；409：乐观锁／幂等请求冲突；503：认证／权限依赖不可用或新鲜度无法确认。各仓保留现有 Result 包装与错误码，通过适配转换，不改全仓接口。
- 不将依赖故障折成 ALLOW；接口显示不可用，不自动扩大角色。故障性 DENY 与明确业务 DENY 在内部 reasonCode 区分，外部只给安全提示。
- 写命令的幂等作用域含企业、应用、环境、操作者、操作、commandId；同键不同 payload 返回冲突；审批重复回调不得重复授权。
- 角色／授权／成员写入带 expectedVersion，影响行数为零不能当成功；角色权限目录版本变化需要预览和确认，旧授权语义有兼容窗口。
- 页面 MePermissions 是展示读模型；Check/ResolveScope 的结果有有限有效期，不能长久保存后复用。
- 内部解释与审计保留命中来源、角色版本、范围、revision、调用应用、管理上限、traceId；不记录 token、邀请 secret、完整地址等敏感资料。

## 6. 分发、失效和操作回执

仅在有消费者的阶段定义事件族：Identity/MembershipChanged、ApplicationAdmissionChanged、RoleVersionPublished、AuthorizationChanged、ObjectRelationChanged。最少携带 `eventId`、`schemaVersion`、tenant/app/environment、主体／资源、revision、changeId、发生时间及必要变更摘要。

消费去重以 eventId／changeId；同作用域只应用更高 revision，乱序旧消息不恢复授权。跨主体／应用没有全局天然顺序。缺失事件可通过权威 revision 拉取补齐；删除或撤权保留墓碑；重放不会新增权限。保留、隔离和人工恢复策略按 Q07 确认。

变更查询返回每受影响应用的 `PENDING/APPLYING/APPLIED/FAILED`、要求版本、已确认版本、重试状态与错误摘要。`APPLIED` 需达到该应用登记的 freshness 约束，并验证对应执行路径；不能只以消息发送成功确认。

若应用离线而收权尚未分发，不能以旧缓存继续处理需新鲜权限的操作。低风险离线许可如需允许，必须先确认最大租约和风险，expiry 到期后拒绝；敏感操作仍要求权威状态。

## 7. 各仓契约演进要求

| 仓库 | 保留 | 新增／适配 | 可停旧路径条件 |
|---|---|---|---|
| auth | AuthzEngine 九项操作、CheckAccess、工作区路由与 schema 隔离 | 新统一协议适配、服务调用范围、一致性／撤权回执；按照本仓规则增量修改 | 既有消费者全迁移并验收；本计划没有旧 SDK 删除动作 |
| OA | `@RequiresPerm`、DataScope、旧 API golden、Casdoor sub、旧 grant 语义 | app/env/member 范围、外部入口、统一协议适配、撤权 freshness | 对应路径通过新旧比较和协议回归；旧组织／审批业务保持 |
| commerce | 原 Actor／actorId/memberId、tenant 条件、本人过滤、store grants、幂等回执 | OIDC 映射、公开授权端口、按动作范围替换试点硬角色、前端权限 | 仅试点路径逐项切换；新细粒度角色不得普遍映射成 ADMIN |

新旧必须有明确路径选择和单写权威；兼容适配不采用 `oldAllow OR newAllow`。已灰度切换的范围必须有保留撤权语义的回退版本，不能回到忽略新授权的旧二进制。

旧对象关系管理入口也要纳入可授予上限、应用／工作区范围和变更审计；未适配的旧高权限入口不能向新应用管理员或外部管理员开放。SpiceDB／管理服务凭据不下发前端或合作方，应用服务持有的凭据限制到已登记的调用职责，不能作为任意主体跨应用判权或写关系的通用凭据。
