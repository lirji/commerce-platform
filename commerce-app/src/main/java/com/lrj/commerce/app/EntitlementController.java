package com.lrj.commerce.app;
import com.lrj.commerce.benefit.api.EntitlementApi;
import com.lrj.commerce.runtime.api.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
/** 内部权益命令明确授权和单位，不冒充现金或外部发奖。 */
@RestController @RequestMapping("/v1")
public class EntitlementController {
    private final EntitlementApi benefits;
    public EntitlementController(EntitlementApi benefits){this.benefits=benefits;}
    /** 不可变定义由运营维护。 */
    @PostMapping("/admin/entitlement-definitions") public Object create(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@RequestBody EntitlementApi.Definition input){return benefits.create(actor,key,input);}
    /** 店铺维度查询发行额度。 */
    @GetMapping("/admin/entitlement-definitions") public Object definitions(@AuthenticationPrincipal Actor actor,@RequestParam String storeId,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return benefits.definitions(actor,storeId,after,limit);}
    /** 本人权益钱包。 */
    @GetMapping("/entitlements") public Object wallet(@AuthenticationPrincipal Actor actor,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return benefits.wallet(actor,after,limit);}
    /** 管理待发放或待补偿条目。 */
    @GetMapping("/admin/entitlements") public Object admin(@AuthenticationPrincipal Actor actor,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return benefits.adminList(actor,after,limit);}
    /** 用户核销需要服务端余额与有效期校验。 */
    @PostMapping("/entitlements/{id}/consume") public Object consume(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@RequestBody EntitlementApi.Consume input){return benefits.consume(actor,key,id,input);}
    /** 账本详情仍校验归属。 */
    @GetMapping("/entitlements/{id}/ledger") public Object ledger(@AuthenticationPrincipal Actor actor,@PathVariable String id,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return benefits.ledger(actor,id,after,limit);}
    /** 人工处理已消费权益的退款欠项，必须有明确凭据与结论。 */
    @PostMapping("/admin/entitlements/{id}/resolve") public Object resolve(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@RequestBody EntitlementApi.Resolution input){return benefits.resolve(actor,key,id,input);}
}
