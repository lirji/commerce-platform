# S5a 验证

PASS：87项，0失败/错误/跳过。真实MySQL8.4.11与HTTP验证6项新增订单场景；编译类边界检查覆盖inventory/order-runtime。证据verify-final.log、verification.json、smoke.json。

并发同键下单单效果、不同订单抢库存无超卖、跨SKU不足全部回滚、取消一次释放、过期报价拒绝、零元确认无伪造收款事件均通过。脚本语法检查及重复seed成功。地址只存AES-GCM密文；密钥必须通过私密env注入。

S5b权益占用保留至S8，Outbox目前仅落库，消费可靠性S6继续；未执行外部渠道联调和生产部署。
