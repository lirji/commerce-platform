package com.lrj.commerce.app.http.member.operations;

import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.kernel.DomainException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 四个写能力分别提示；真实策略、会员和权益引用仍由提交用例重新验证。 */
@RestController
@ConditionalOnProperty(name = {"commerce.iam.store-read.enabled", "commerce.iam.employee.enabled"}, havingValue = "true")
@RequestMapping("/v1/operations")
public class CycleActionsController {
    private final EmployeeAccess access;
    public CycleActionsController(EmployeeAccess access) { this.access = access; }
    /** 发布不要求政策读取。 */
    @GetMapping("/member-cycles/publish-access")
    public ActionAccess publish(@AuthenticationPrincipal Actor actor) { return check(actor, EmployeeAccess.Capability.MEMBER_CYCLE_POLICY_PUBLISH); }
    /** 考核提示不授予指定会员读取。 */
    @GetMapping("/member-cycles/evaluate-access")
    public ActionAccess evaluate(@AuthenticationPrincipal Actor actor) { return check(actor, EmployeeAccess.Capability.MEMBER_CYCLE_EVALUATE); }
    /** 定义礼包只使用自身能力，关联政策在事务内由Owner验证。 */
    @GetMapping("/member-cycle-benefits/define-access")
    public ActionAccess define(@AuthenticationPrincipal Actor actor) { return check(actor, EmployeeAccess.Capability.CYCLE_BENEFIT_DEFINE); }
    /** 补发提示不承诺存在可发权益。 */
    @GetMapping("/member-cycle-benefits/grant-access")
    public ActionAccess grant(@AuthenticationPrincipal Actor actor) { return check(actor, EmployeeAccess.Capability.CYCLE_BENEFIT_GRANT); }
    private ActionAccess check(Actor actor, EmployeeAccess.Capability capability) {
        if (actor == null || actor.role() != Actor.Role.OPERATOR || actor.executionId() == null)
            throw new DomainException(DomainException.Code.FORBIDDEN, "中央周期授权拒绝");
        var before = access.scope(actor, capability);
        EmployeeAccess.requireSame(before, access.scope(actor, capability));
        return new ActionAccess(true);
    }
    /** 提示不成为可复用授权许可。 */
    public record ActionAccess(boolean allowed) {}
}
