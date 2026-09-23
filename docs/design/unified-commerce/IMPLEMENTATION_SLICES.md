# 实施路线与切片

用户已授权新建与开始实施。首个执行视野 M0 是领域内核工程基线；其后是实际数据库/API 的 M1。总体平台未完成，不将纯计算内核称为业务全链路。当前顺序全部串行。

| ID | 可观察结果与验收 | 依赖 | Owner/路径 | Runtime | 状态 |
|---|---|---|---|---|---|
| S0 | 来源证据、全景方案、C1–C4、工程基线可审阅 | — | design/docs | 无 | DONE |
| S1 | 版本化活动条件树参与报价；未知拒绝；分摊守恒、稳定择优、资源越界拒绝 | S0 | backend/shared-kernel、marketing | Java 21/Maven；C1–C3 | DONE（26项，s1-maven.log） |
| S2 | 取消/支付竞态、可信未支付确认、履约、非法迁移按 C4 验证 | S1 | backend/order | Java；C4 | DONE（45项） |
| S3 | 编译/行为/模块边界测试通过，恢复记录绑定产物 | S2 | validation/architecture-tests、docs | Maven offline verify | DONE（总72项） |
| S4 | 单店最小业务主数据→活动发布→报价保存/查询→重启可回放 | S3 | backend+runtime；member/merchant/store/catalog/trade/装配 | 隔离 MySQL、Spring/MyBatis/Flyway 版本核验 | DONE（真实MySQL/HTTP/重启证据s4） |
| S5 | 报价消费、订单/库存/权益预占原子提交；重复/并发/失败回滚 | S4 | order/inventory/benefit | 真实 DB 集成；不能只 Mock | TODO |
| S6 | 隔离支付适配、支付未知/关单、事件可靠投递与重复消费 | S5 | payment/jobs/装配 | 渠道沙箱及 DB；真实渠道另需明确配置 | TODO |
| S7 | 履约、退货、退款及权益冲正，补偿与对账闭环 | S6 | fulfillment/aftersales | WMS/渠道契约或隔离沙箱 | TODO |
| S8 | 活动、人群、规则版本发布、权益及券、叠加/资金分摊 | S4–S7 | marketing/benefit/trade | 授权、审计、规则与人群来源 | TODO |
| S9 | 旅程实例、等待/触达/超时/取消、恢复和运营低代码发布 | S8 | marketing/journey/lowcode | 持久调度、渠道隔离 | TODO |
| S10 | 管理台和消费端真实接口、数据库 seed、部署文件与全链路验证 | 各后端 API 完成时逐页跟进，最终依赖 S9 | frontend/runtime | 沿用已有设计资产，另出前端架构 | TODO |

S4–S10 是里程碑切片候选，实施前按单次有界闭环细化，保留这些 ID 为父项，不一次铺完空模块。DB runtime 在 S4 首次介入；API 前先冻结授权/身份/主数据/幂等/DTO 契约。S1–S3 只接受 C1–C4，不连接旧系统，不解除旧规则迁移。

首批验证矩阵：成功与拒绝、租户/店铺错配、缺失事实与 NOT UNKNOWN、超深/超大条件树、集合不可变、金额精度及溢出、时间边界、平局稳定性、分摊守恒、所有状态×事件组合、版本溢出、领域无外部技术依赖。数据库/HTTP/消息/容量验收不适用于首批，但必须作为后续未完成项保留。
