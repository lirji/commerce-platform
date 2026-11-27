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

OA-00 本地基线为商城 `45bbb45`（V84）、交易 `b77245e`（V050）、营销 `4793fd1`（资金 V20、发布 V16）。商城隔离验收使用真实 MySQL 与测试专岗。业务日量、1 分钟和 5 分钟峰值从获准环境的请求及数据库指标采集，行数和字节从审计表和索引实测。当前无峰值采样，生产放量按 OA-10 实测签收。

## OA-02 分类字典与开店

- 类目创建记 `platform.category.create`。原变更接口仍沿用 `platform.category.change` 幂等范围，成功动作按实际差异选用恢复、停用、改名或排序编码。多个字段一起修改只写一份效果，按启停、改名、排序的次序选择主动作，完整差异可查。重试引用原效果及其动作。授权与服务器校验拒绝保留尝试动作。
- `GET /v1/admin/store-openings/{storeId}/operation-audits` 沿用 `STORE_CREATE` 的当前开店资料权限，核对同一租户与店铺。无权平台或会员返回 403。其它租户店铺按资料接口语义返回 404。返回结构同首期审计分页，默认仅查该门店近 31 天的草稿、补正和确认开店，每页 20 条，接受 `cursor`。中央身份与旧租户管理员均沿用已有权威路由。
- 开店记录主体类型、公开名称、经营类目编码、状态、资料版本和受控审核引用。名称与审核引用应用文本遮蔽。实际效果和已有员工授权审计共同在当前事务提交。V86 给成功记录新增可空的实际动作编码，并给租户开店查询增加有界索引。

## OA-03 店铺商品类目

- 带平台类目关联的新建和修改，以及显式解除关联，分别记录尝试和实际变化。主动作由提交前后标准类目编码确定。提交被拒绝或重试时保留尝试和原效果引用，无权限门店仅留最小入口证据。
- `GET /v1/operations/catalog-categories/{id}/operation-audits?storeId=...` 复用当前商品经营授权。范围固定为可信租户、已核验门店与实际分类，默认最近 31 天，每页 20 条，支持既有 `cursor`。V87 增加可空门店字段和精确复合索引。

## OA-07 试点原生交易同单查询

- `GET /v1/admin/order-operation-evidence` 只接受精确的交易展示订单号，`source` 支持 `ALL`、`RECEIPTS`、`REFUNDS`、`MARKETING`。分源游标沿用各自来源。初始默认关闭。
- 商城按签收配置选定单一门店，复核当前用户订单、支付和退款权限。交易持久关联的商城租户、商家、门店、范围版本及报价必须与本地报价相符。营销读取追加营销证据权限，并核对资金申请和同一交易报价引用。权限在下游查询后复核。
- 下游使用固定地址、独立授权凭据和有界超时。营销不可用时保留已取得的报价及收退款。交易绑定缺失时保留未关联状态或统一返回本店无记录。无跨店回退和宽条件检索。
- 营销决策代次取自已绑定资金申请。发布组合版本和决策代次目前在独立发布与资金查询中各自展示。运行端关联需要目标环境的同单发布凭证复核。

## OA-08 商城档案验证与恢复

- V88 建立验证清单、固定成员、恢复工单和受控查询区。XXL-JOB 处理器为 `commerceAuditArchiveVerify`。读取严格限定服务端配置的源租户和范围，每轮最多 200 条，按提交过实际效果或已知结果的尝试固定批次。
- 档案为带格式版本和批次号的 `NDJSON.gz`，通过条件写保护单批对象键。保存精确对象版本、压缩后 SHA-256、字节数和条数，回读交叉比对快照与关联。验证路径使用单独的凭据并保留在线内容，恢复使用只有版本读取权限的独立凭据。
- 平台人员从 `/v1/platform/audit-archive/restorations/{ticket}` 查询，租户管理员从 `/v1/admin/audit-archive/restorations/{ticket}` 查询。对同一路径追加 `/restore` 并用 POST 恢复。当前操作者、租户、授权范围、已验证批次、授权期限和工单撤销状态必须同时匹配。允许身份与不同的审批身份由既有工单授权流程登记到 `audit_archive_restore_grant`，应用恢复接口只消费已批准的记录。
- 默认关闭归档和调度。配置 `COMMERCE_AUDIT_ARCHIVE_*` 时，写入身份仅获桶版本状态及归档前缀的读写，恢复身份仅获该前缀的已有版本读取。归档最低年龄只控制验证范围。正式保留周期和销毁开关在后续生命周期签收。条件写与指定版本读取采用 S3 客户端公开契约。 [条件写](https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/services/s3/model/PutObjectRequest.html#ifNoneMatch()) · [指定版本读取](https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObject.html)

## OA-09 清理前复核

`commerceAuditArchiveRetentionReview` 按单一批次读取持久策略和案件冻结。V89 按范围、租户、策略类型和写入时版本保存签收记录。线上与总保存时间由最近一次已确认事实时间推导，未核实记录保持不具备清理资格。范围冻结可跨多个批次，单个业务对象也可单独冻结。复核通过须再次精确回读已核验的对象并与在线行比对。作业返回状态与最早日期供后续审批与清理事务使用。

### 商城在线副本切换

`commerceAuditArchiveOnlineCutover` 默认关闭。V90 增加批次独立批准、在线清理回执、原效果定位和晚到追加记录。执行任务输入一个当前来源范围内的批次编号。实际在线切换在锁内再次核对档案、策略、冻结、成员和活跃幂等命令，按实际条数提交批次、定位和回执。重复任务返回已移出。已在档案中的原证据继续通过授权工单按版本恢复，后到证据通过原尝试引用追加。

对象到期销毁、旧备份恢复时重放删除状态和清理责任跨系统验收仍待实现和验证。启用在线切换需要所有实例先支持 V90 的原尝试行锁及晚到追加路径，并完成目标环境恢复与保留审批。

### 从备份恢复后补放在线清理

V91 把清理回执与数据库备份分开保全。在线切换先在版本化对象存储写入准备记录，再事务内提交删除与回执。正常切换结束后导出提交凭证。进程中断可由 `commerceAuditArchivePublishRecovery` 每轮补发一个未完成回执。凭证只保存审批、档案版本、计数和逐行指纹，按固定对象键写入，回读通过后保存对象版本及 SHA-256。

恢复目标使用隔离库并单独开启 `COMMERCE_AUDIT_ARCHIVE_RECOVERY_REPLAY_ENABLED`。源库停止切换后，用 `commerceAuditArchiveRecoveryInventory` 按配置范围逐页清点，初始任务参数为空。后续页参数为上次返回的游标。恢复身份对清理凭证前缀需要 `s3:ListBucket`、`s3:GetObject` 和指定版本读取权限。对象存储使用支持列表分页及精确版本读取的版本化桶。 [S3 ListObjectsV2](https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListObjectsV2.html) · [S3 版本读取](https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObject.html)

清点发现准备记录缺少提交凭证时，该页作业失败并记录未决批次。须对照源库或独立灾备记录厘清实际事务结果后继续；完成所有页之前保持恢复目标隔离。已确认的每份凭证以任务输出的 JSON 作为 `commerceAuditArchiveReplayRecovery` 参数。补放校验作用域、对象精确版本、长度、摘要、成员和仍在线的子证据，按事务移除匹配副本、恢复定位信息并记录已应用状态。冲突时保留整批，由恢复负责人核对仍活跃的原幂等命令或冻结。恢复完成后再开放业务读写。
