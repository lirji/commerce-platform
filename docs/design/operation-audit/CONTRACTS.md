# 商城操作审计 v1 首期契约

OA-00 约定商城首个纵向切片。共同语义适用于交易和营销，各来源继续拥有原业务证据。

- `sourceSystem` 表示实际写入系统。`requestId` 是服务端产生的一次请求编号。`traceId` 复用可信 HTTP 追踪标识。`operationRef` 用于跨系统关联，OA-01 尚未跨系统传播。业务编号、请求和实际提交分别有各自引用。
- `platform.category.disable` 捕获已有 `POST /v1/platform/commerce-categories/{code}` 中显式提交 `enabled=false` 的尝试。现有鉴权、`Idempotency-Key`、版本校验和业务响应继续适用。具有平台字典读取权限时，可保存校验过格式的请求类目编号。其他授权拒绝仅记录可信主体和最小入口信息。
- `GET /v1/platform/operation-audits` 按可信平台范围查询。`from`、`to` 为 ISO 8601 UTC 时间，`from` 包含端点、`to` 排除端点。默认最近七天，最大 31 天。可按 `actorId`、`resourceId`、`action`、`result` 精确查询，`limit` 默认 50 且最大 100，分页使用响应的 `nextCursor` 原样传回。返回 `items` 和可空的 `nextCursor`。
- `GET /v1/platform/operation-audits/{id}` 返回该次尝试和证据。`id` 是十进制字符串表示的本地尝试 ID。列表项、详情字段为 `id`、`sourceSystem`、`requestId`、`traceId`、`action`、`actorId`、`resourceId`、`occurredAt`、`result`、`errorCode`、`effectId`、`originalAttemptId`、`beforeVersion`、`afterVersion`、`reason`、`changes`。证据字段在未发生或不可取得时为空。实际差异是 `field`、`before`、`after` 对象列表，使用字段白名单和 JSON 原始类型。`effectId` 对应来源提交证据，`originalAttemptId` 用于打开重试的原始尝试。
- 接口采用现有错误响应 `code`、`message`、`traceId`，包括 400 参数错误、401 无身份、403 无权限、404 记录不存在和 503 审计暂不可用。结果码为 `SUCCEEDED`、`REPLAYED`、`CONFLICT`、`DENIED`、`REJECTED`、`FAILED`、`UNKNOWN`。超时或进程中断时，已提交证据优先于未核实结果。重试链接原提交而不重复产生业务效果。
- 平台操作员具备平台范围审计读取能力，类目写入仍须专岗授权。租户管理员、门店运营和会员无权读取平台记录。实际列表与详情读取在显示前以 `platform.audit.list` 或 `platform.audit.read` 留痕。默认动作范围聚焦业务操作，审计查询动作可显式筛选。租户查询、导出、归档及冻结在相应切片单独定权限。
- 旧成功记录通过原类目历史及原审计来源查询。OA-01 新视图显示切换后的尝试，后续兼容读取保留来源和缺失字段语义。自由文本只接受本动作允许的字段并执行敏感内容遮蔽。日志禁止收集凭据、请求体和异常堆栈。

样例。原类目变更成功为 `platform.category.disable / SUCCEEDED`，差异包含 `enabled: true → false`。原键重试为 `REPLAYED`，关联首次尝试和相同效果 ID。过期版本为 `CONFLICT`，非专岗为 `DENIED`，两者的成功差异为空。

OA-00 本地基线为商城 `45bbb45`（V84）、交易 `b77245e`（V033）、营销 `4793fd1`（资金 V20、发布 V16）。商城隔离验收使用真实 MySQL 与测试专岗。业务日量、1 分钟和 5 分钟峰值从获准环境的请求及数据库指标采集，行数和字节从审计表和索引实测。当前无峰值采样，生产放量按 OA-10 实测签收。

## OA-02 分类字典与开店

- 类目创建记 `platform.category.create`。原变更接口仍沿用 `platform.category.change` 幂等范围，成功动作按实际差异选用恢复、停用、改名或排序编码。多个字段一起修改只写一份效果，按启停、改名、排序的次序选择主动作，完整差异可查。重试引用原效果及其动作。授权与服务器校验拒绝保留尝试动作。
- `GET /v1/admin/store-openings/{storeId}/operation-audits` 沿用 `STORE_CREATE` 的当前开店资料权限，核对同一租户与店铺。无权平台或会员返回 403。其它租户店铺按资料接口语义返回 404。返回结构同首期审计分页，默认仅查该门店近 31 天的草稿、补正和确认开店，每页 20 条，接受 `cursor`。中央身份与旧租户管理员均沿用已有权威路由。
- 开店记录主体类型、公开名称、经营类目编码、状态、资料版本和受控审核引用。名称与审核引用应用文本遮蔽。实际效果和已有员工授权审计共同在当前事务提交。V86 给成功记录新增可空的实际动作编码，并给租户开店查询增加有界索引。
