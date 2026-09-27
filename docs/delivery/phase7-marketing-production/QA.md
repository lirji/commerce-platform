# Phase 7 QA

- Gate：`PASS_WITH_LIMITATIONS`，最终产品代码 clean build 通过；应用 279/0/5 skips、架构 3/3、纯决策/规则 27/27，UI 入 jar。
- 实际 OLD df955f1 + NEW 同 MySQL V43：旧 CREDIT 生产链由新节点完成；NEW completion 在 OLD 不领取后由 NEW 唯一消费。
- 最终 NEW + NEW 独立进程：每种权益 120 用户/额度 30，30 成功/90 冲突、最终 30 grant、reserved=0；锁/方法分段有实际结果。
- COUPON：支付成功、取消、最后额度竞争、事务效果后失败回滚恢复、双实例重复事件、来源唯一与执行类型通过。
- BEST_OF：清晰赢家、平局、候选反序、竞争预览与真实 quote 一致；删除 ID 破平局 mutation 被检出并恢复。
- 规模：4,000 历史发布/80 有效、50k 执行/grant/event/Inbox、50k 人群存储探针，实际计划及 HTTP 三层/情景/三租户结果有界；未抬产品上限。
- Browser：最终 jar 受影响 2/2；测试补充等待异步退款，不改变用户业务流程。
- 文件检查：`git diff --check`；令牌/密码、临时 class、原始应用安全密码日志留在忽略 `.local`，未纳入交付。

限制和失败历史详见 `15-regression.md`、容量与滚动证据。无长期 soak/生产峰值/外部适配器/真实网络分区认证；Phase 5 全浏览器集合未重跑。已授权范围内无剩余实施门禁；真实生产上线需环境与部署授权。

最终兼容收尾：报价新 enum 写入受独立默认关闭的 extended-trace 门禁保护；最后应用 279/0/5，OLD 对最终 NEW 报价读取/下单/取消均 200，最终浏览器 2/2（17.3 s）。新能力开启后回退目标必须保留能力，等待 TTL 不会自动清理历史枚举。

后续明确授权的 Git 交付：重新 clean build 279/0/5、架构3/3；分批暂存快照78项通过；新打包jar浏览器2/2（18.1 s）。见 DELIVERY_RESULT.md。
