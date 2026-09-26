package com.lrj.commerce.app;
import com.lrj.commerce.payment.api.PaymentApi;
import com.lrj.commerce.ordering.api.OrderApi;
import com.lrj.commerce.runtime.EventDispatcher;
import com.lrj.commerce.runtime.api.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
/** 真实资金状态只能来自适配器查询，浏览器不提供成功回调。 */
@RestController @RequestMapping("/v1")
public class PaymentController {
    private final PaymentApi payments;private final EventDispatcher events;private final OrderApi orders;
    public PaymentController(PaymentApi payments,EventDispatcher events,OrderApi orders){this.payments=payments;this.events=events;this.orders=orders;}
    /** 发起支付仅保存意图，UNKNOWN响应不能展示为已付款。 */
    @PostMapping("/orders/{id}/payments") public Object start(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id){return payments.start(actor,key,id);}
    /** 查询有订单归属约束。 */
    @GetMapping("/orders/{id}/payment") public Object read(@AuthenticationPrincipal Actor actor,@PathVariable String id){return payments.read(actor,id);}
    /** 核对操作天然幂等，终态只向前推进。 */
    @PostMapping("/orders/{id}/payment/reconcile") public Object reconcile(@AuthenticationPrincipal Actor actor,@PathVariable String id){return payments.reconcile(actor,id);}
    /** 管理员操作的是隔离渠道，正式运行默认不可用。 */
    @PostMapping("/admin/sandbox/payments/{id}/fact") public Object sandbox(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@RequestBody PaymentApi.SandboxFact fact){return payments.sandboxFact(actor,key,id,fact);}
    /** 到期处理不能直接把支付中订单判为未付。 */
    @PostMapping("/admin/orders/expire") public Object expire(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key){return orders.expire(actor,key);}
    /** 停止自动到期的订单在修复数据后审计重试，保留最近失败证据。 */
    @PostMapping("/admin/orders/{id}/expiry/retry") public Object retryExpiry(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id){return orders.retryExpiry(actor,key,id);}
    /** 手动运行一次有界当前租户事件批次。 */
    @PostMapping("/admin/events/pump") public Object pump(@AuthenticationPrincipal Actor actor){return events.pump(actor);}
    /** 仅查询租户内事件元信息。 */
    @GetMapping("/admin/events") public Object list(@AuthenticationPrincipal Actor actor,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return events.list(actor,after,limit);}
    /** 本租户积压诊断：到期数、最老到期年龄、重试与隔离数、近期投递延迟，不含载荷。 */
    @GetMapping("/admin/events/health") public Object health(@AuthenticationPrincipal Actor actor){return events.health(actor);}
    /** 隔离或跳过的事件需要审计的重放命令，只执行未成功的消费者。 */
    @PostMapping("/admin/events/{id}/retry") public Object retry(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id){return events.retry(actor,key,id);}
}
