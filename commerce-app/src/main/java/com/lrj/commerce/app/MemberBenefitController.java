package com.lrj.commerce.app;
import com.lrj.commerce.benefit.api.MemberBenefitApi;
import com.lrj.commerce.runtime.api.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 等级礼包管理不接受伪造会员发放身份。 */
@RestController @RequestMapping("/v1/admin/member-cycle-benefits")
public class MemberBenefitController {
    private final MemberBenefitApi benefits;
    public MemberBenefitController(MemberBenefitApi benefits){this.benefits=benefits;}
    /** 发布经过策略/权益窗口校验的不可变礼包。 */
    @PostMapping public Object publish(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@RequestBody MemberBenefitApi.Bundle input){return benefits.publish(actor,key,input);}
    /** 每策略最多8档，列表有界。 */
    @GetMapping public Object list(@AuthenticationPrincipal Actor actor,@RequestParam long policyVersion){return benefits.list(actor,policyVersion);}
    /** 补发当前周期，与自动发放共享来源唯一键。 */
    @PostMapping("/{id}/grant") public Object grant(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id){return benefits.grant(actor,key,id);}
}
