package com.lrj.commerce.app;
import com.lrj.commerce.member.api.*;
import com.lrj.commerce.catalog.api.CatalogApi;
import com.lrj.commerce.ordering.api.OrderApi;
import com.lrj.commerce.runtime.Commands;
import com.lrj.commerce.runtime.api.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
/** app装配目录、订单与会员端口，领域不反向依赖交易。 */
@RestController @RequestMapping("/v1")
public class MemberBehaviorController {
    private final MemberBehaviorApi behavior;private final MemberApi members;private final CatalogApi catalog;private final OrderApi orders;private final Commands commands;
    public MemberBehaviorController(MemberBehaviorApi behavior,MemberApi members,CatalogApi catalog,OrderApi orders,Commands commands){this.behavior=behavior;this.members=members;this.catalog=catalog;this.orders=orders;this.commands=commands;}
    /** 管理员会员详情。 */
    @GetMapping("/admin/member-behavior/{id}") public Object detail(@AuthenticationPrincipal Actor actor,@PathVariable String id){actor.requireAdmin();return behavior.detail(actor,id);}
    /** 管理更新偏好保留原因审计。 */
    @PostMapping("/admin/member-behavior/{id}/profile") public Object profile(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@RequestBody MemberBehaviorApi.ProfileChange input){actor.requireAdmin();return behavior.profile(actor,key,id,input);}
    /** 管理行为明细不允许跨租户。 */
    @GetMapping("/admin/member-behavior/{id}/events") public Object events(@AuthenticationPrincipal Actor actor,@PathVariable String id,@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="50") int limit){actor.requireAdmin();return behavior.events(actor,id,after,limit);}
    /** 本人资料和统计。 */
    @GetMapping("/members/me/behavior") public Object mine(@AuthenticationPrincipal Actor actor){return behavior.detail(actor,members.current(actor).memberId());}
    /** 本人可关闭旅程触达。 */
    @PostMapping("/members/me/behavior/profile") public Object mineProfile(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@RequestBody MemberBehaviorApi.ProfileChange input){return behavior.profile(actor,key,members.current(actor).memberId(),input);}
    /** 商品交互必须指向真实可售目录，不能写任意SKU统计。 */
    @PostMapping("/members/me/behavior/events") public Object record(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@RequestBody MemberBehaviorApi.Signal input){Inputs.require(input!=null,"行为内容缺失");com.lrj.commerce.kernel.Identifiers.require(input.skuId());com.lrj.commerce.kernel.Identifiers.require(input.storeId());if(actor.role()!=Actor.Role.MEMBER)throw new com.lrj.commerce.kernel.DomainException(com.lrj.commerce.kernel.DomainException.Code.FORBIDDEN,"仅会员可记录交互");return commands.run(actor,"member.behavior.record",key,input,MemberBehaviorApi.Event.class,()->{catalog.published(actor,input.storeId(),List.of(input.skuId()));return behavior.record(actor,input);});}
    /** 本人行为记录。 */
    @GetMapping("/members/me/behavior/events") public Object mineEvents(@AuthenticationPrincipal Actor actor,@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="50") int limit){return behavior.events(actor,members.current(actor).memberId(),after,limit);}
    record Rebuild(String after,int limit) { }
    record Progress(String next,int scanned,boolean done) { }
    /** 每次最多50单，调用领域端口补建，不跨领域直写订单或会员表。 */
    @PostMapping("/admin/member-behavior/rebuild") public Object rebuild(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@RequestBody Rebuild input){
        actor.requireAdmin();Inputs.require(input!=null && input.limit()>0 && input.limit()<=50,"补建批次1至50");Inputs.page(input.after(),input.limit());
        return commands.run(actor,"member.behavior.rebuild",key,input,Progress.class,()->{var batch=orders.adminList(actor,input.after(),input.limit());for(var order:batch)behavior.projectOrder(actor.tenantId(),order.orderId(),order.createdAt());return new Progress(batch.isEmpty()?input.after():batch.getLast().orderId(),batch.size(),batch.size()<input.limit());});
    }
}
