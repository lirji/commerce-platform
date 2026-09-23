package com.lrj.commerce.app;
import com.lrj.commerce.benefit.api.PointOfferApi;
import com.lrj.commerce.runtime.api.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
/** 积分兑换入口，会员主体固定来自认证而非请求参数。 */
@RestController @RequestMapping("/v1")
public class PointOfferController {
    private final PointOfferApi offers;
    public PointOfferController(PointOfferApi offers){this.offers=offers;}
    /** 创建规则必须管理员。 */
    @PostMapping("/admin/point-offers") public Object create(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@RequestBody PointOfferApi.Offer input){return offers.create(actor,key,input);}
    /** 停启有版本与审计。 */
    @PostMapping("/admin/point-offers/{id}/status") public Object status(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@RequestBody PointOfferApi.Status input){return offers.status(actor,key,id,input);}
    /** 管理员查看全部目录。 */
    @GetMapping("/admin/point-offers") public Object adminList(@AuthenticationPrincipal Actor actor,@RequestParam String storeId,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){actor.requireAdmin();return offers.list(actor,storeId,after,limit);}
    /** 会员只可见当前有效目录。 */
    @GetMapping("/point-offers") public Object list(@AuthenticationPrincipal Actor actor,@RequestParam String storeId,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return offers.list(actor,storeId,after,limit);}
    /** 原子扣分和发放受理。 */
    @PostMapping("/point-offers/{id}/redeem") public Object redeem(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id){return offers.redeem(actor,key,id);}
    /** 会员本人回执分页。 */
    @GetMapping("/point-redemptions") public Object receipts(@AuthenticationPrincipal Actor actor,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return offers.receipts(actor,after,limit);}
}
