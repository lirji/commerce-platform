# 企业功能建设报告

状态：设计完成，实施进行中；不可将本报告视为已实现能力声明。

现状证据：MemberService仅create/current/list；CatalogService仅create/list/published；MarketingAssets可信事实只有memberLevel/orderAmount；JourneyApi触发只有MANUAL/ORDER_PAID；平台角色只有ADMIN/MEMBER。订单与资金链、Commands幂等、Outbox/Inbox、真实MySQL验证可复用。原有145测试为历史基线，本轮待执行。

未知项：身份/门店授权范围、会员注销语义、成长计量及退款、商品变价快照、动态人群原子发布、活动效果口径。决定与边界见TECH_SELECTION、BACKEND_ARCHITECTURE和CONTRACTS。用户授权覆盖上述两期内部建设及合理技术引入；默认人民币、单租户内会员统一身份，不自动跨品牌合并。

影响：V16起追加迁移、领域API兼容扩展、后台导航和管理表单、消费规则事实、异步事件处理、测试/seed/文档。高风险路径是权限、成长冲正、报价资金与历史兼容；必须真实数据库验证。既有可用架构不拆服务、不新建通用平台。

ResearchGate=PASS（复用栈、官方锁/调度语义已核查）；ArchitectureGate=PASS_WITH_ASSUMPTIONS（运营初始策略需用户/运营配置，不提供默认经济承诺）；ImplementationGate=PASS（用户计划后实施授权）。交付门禁保持PENDING直到真实验证完成。
