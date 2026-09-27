# 滚动部署与回退顺序

1. 备份及核对 V41 数据与事件积压；应用 V42、V43。旧 jar 在扩展 schema 上启动已测。
2. 部署 NEW，所有节点保持 `COMMERCE_MARKETING_COUPON_ENABLED=false` 和 `COMMERCE_MARKETING_EXTENDED_TRACE_ENABLED=false`。OLD/NEW 并存期间只允许原 CREDIT 与旧活动配置；NEW 接收旧事件并消费新 `available` 事件，观察 `PENDING`、`unrouted`、隔离与 grant 投影。
3. 确认 OLD 全部退出、事件积压清零或有明确归属后，按需在所有 NEW 节点启用券活动能力及扩展 Trace；再创建/审批/发布券活动。不能在一部分旧节点仍服务请求时启用。
4. 观察报价延迟、候选数量、券 `reserved+issued<=quota`、营销执行和事件失败；异常时先暂停新券活动并停止新券报价流量。

代码回退保留 V42/V43，不做 Flyway down。券和扩展 Trace 均未开启前，可回退 OLD 并保留扩展 schema。券活动已开启后的默认回退目标必须仍支持 COUPON；先暂停、等待 5 分钟报价 TTL 与 15 分钟订单付款窗，并核对 `benefit_campaign_coupon_hold` 中 `HELD` 数量，处理在途订单。不能仅因 HELD=0 就宣称回退 OLD 安全：旧解码器会忽略 `terms.coupon`，旧节点若重新发布或接收券活动报价将丢失赠券承诺。跨此功能激活点回退 OLD 必须同时隔离券活动流量和相关管理入口，并另行验证；本轮未认证该操作。已发券属于业务效果，不随 jar 回退撤销；配置回退只影响未来报价。必要补偿须独立按券业务状态处理，不能自动删钱包券。

扩展 Trace 是另一独立激活点：OUTRANKED_BEST_OF 旧 enum 无法解码，实测 OLD 读取含该值的新报价 HTTP 500。默认关闭时 NEW 保存原 ELIGIBLE，仅适配解释字段，不改变赢家/金额。开启后历史 quote/命令结果含新枚举，等待报价 TTL 也不会移除历史值；必须回退到支持扩展 enum 的版本，不能改写历史来假造兼容。
