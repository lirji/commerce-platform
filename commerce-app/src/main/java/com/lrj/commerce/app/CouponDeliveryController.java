package com.lrj.commerce.app;
import com.lrj.commerce.journey.api.CouponDeliveryApi;
import com.lrj.commerce.runtime.api.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
/** 定向发券管理员入口，不接受客户端直接执行任意发券SQL。 */
@RestController @RequestMapping("/v1/admin/coupon-deliveries")
public class CouponDeliveryController {
    private final CouponDeliveryApi deliveries;
    public CouponDeliveryController(CouponDeliveryApi deliveries){this.deliveries=deliveries;}
    /** 固定规则及人群版本。 */
    @PostMapping public Object create(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@RequestBody CouponDeliveryApi.Create input){return deliveries.create(actor,key,input);}
    /** 批次游标分页。 */
    @GetMapping public Object list(@AuthenticationPrincipal Actor actor,@RequestParam String storeId,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return deliveries.list(actor,storeId,after,limit);}
    /** 收件人只读回执。 */
    @GetMapping("/{id}/recipients") public Object recipients(@AuthenticationPrincipal Actor actor,@PathVariable String id,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return deliveries.recipients(actor,id,after,limit);}
    /** 停止、恢复与撤销都有审计原因。 */
    @PostMapping("/{id}/control") public Object control(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@RequestBody CouponDeliveryApi.Control input){return deliveries.control(actor,key,id,input);}
    /** 手动推进不绕过批次和限额。 */
    @PostMapping("/pump") public Object pump(@AuthenticationPrincipal Actor actor){return deliveries.pump(actor);}
}
