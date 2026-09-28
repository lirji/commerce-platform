# Journey领域模型

既有marketing-automation独占定义、实例、cap、站内信、effect与新step表。会员/规则/权益/订单通过已有公开API协作；没有新服务、BPM引擎、消息中间件或通用工作流平台。

固定Definition+有限DAG→触发来源去重→Instance固定version→单节点短事务→真实业务action→检查点。`BACKEND_ARCHITECTURE.md`和`CONTRACTS.md`为正式方案。既有Definition JSON不增加字段；Kind保持WAIT/DECIDE/GRANT/COUPON/NOTIFY/END。

运行配置是明确节点字段，无任意脚本、Webhook、并行分支、运行中人工审批或隐式补偿。金额等事实仍由规则/领域API负责，Journey不自造金额规则。
