package com.lrj.commerce.app;
import com.lrj.commerce.aftersales.api.AftersaleApi;
import com.lrj.commerce.fulfillment.api.FulfillmentApi;
import com.lrj.commerce.payment.api.RefundApi;
import com.lrj.commerce.runtime.api.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
/** 履约与售后协议层不计算退款金额，不允许直接修改业务状态。 */
@RestController @RequestMapping("/v1")
public class AftersaleController {
    private final AftersaleApi cases;private final FulfillmentApi fulfillment;private final RefundApi refunds;
    public AftersaleController(AftersaleApi cases,FulfillmentApi fulfillment,RefundApi refunds){this.cases=cases;this.fulfillment=fulfillment;this.refunds=refunds;}
    /** 会员只看本人物流。 */
    @GetMapping("/orders/{id}/fulfillment") public Object fulfillment(@AuthenticationPrincipal Actor actor,@PathVariable String id){return fulfillment.read(actor,id);}
    /** 有界管理待履约列表。 */
    @GetMapping("/admin/fulfillments") public Object fulfillments(@AuthenticationPrincipal Actor actor,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return fulfillment.list(actor,after,limit);}
    /** 隔离WMS发货事实。 */
    @PostMapping("/admin/fulfillments/{id}/ship") public Object ship(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@RequestBody FulfillmentApi.Ship input){return fulfillment.ship(actor,key,id,input);}
    /** 隔离WMS送达事实。 */
    @PostMapping("/admin/fulfillments/{id}/deliver") public Object deliver(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id){return fulfillment.deliver(actor,key,id);}
    /** 申请只包含数量与原因，金额由订单快照计算。 */
    @PostMapping("/aftersales") public Object request(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@RequestBody AftersaleApi.Request input){return cases.request(actor,key,input);}
    /** 个人申请明细。 */
    @GetMapping("/aftersales/{id}") public Object read(@AuthenticationPrincipal Actor actor,@PathVariable String id){return cases.read(actor,id);}
    /** 个人申请列表。 */
    @GetMapping("/aftersales") public Object list(@AuthenticationPrincipal Actor actor,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return cases.list(actor,after,limit);}
    /** 管理员审核列表。 */
    @GetMapping("/admin/aftersales") public Object admin(@AuthenticationPrincipal Actor actor,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return cases.adminList(actor,after,limit);}
    /** 批准申请，已发货必须等待退货。 */
    @PostMapping("/admin/aftersales/{id}/approve") public Object approve(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id){return cases.approve(actor,key,id);}
    /** 驳回解除发货阻拦。 */
    @PostMapping("/admin/aftersales/{id}/reject") public Object reject(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id){return cases.reject(actor,key,id);}
    /** 可信退货收货命令。 */
    @PostMapping("/admin/aftersales/{id}/receive-return") public Object receive(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id){return cases.receiveReturn(actor,key,id);}
    /** 财务退款查询不返回渠道机密。 */
    @GetMapping("/admin/refunds") public Object refunds(@AuthenticationPrincipal Actor actor,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return refunds.list(actor,after,limit);}
    /** 核对可信退款渠道。 */
    @PostMapping("/admin/refunds/{id}/reconcile") public Object reconcile(@AuthenticationPrincipal Actor actor,@PathVariable String id){return refunds.reconcile(actor,id);}
    /** 沙箱操作不等于银行退款完成。 */
    @PostMapping("/admin/sandbox/refunds/{id}/success") public Object success(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id){return refunds.sandboxSuccess(actor,key,id);}
}
