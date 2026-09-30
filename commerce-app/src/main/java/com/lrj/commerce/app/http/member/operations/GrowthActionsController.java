package com.lrj.commerce.app.http.member.operations;

import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.kernel.DomainException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 三种写入资格分别查询，不隐含会员读取；对象资格在实际提交时由Owner重新核验。 */
@RestController
@ConditionalOnProperty(name = {"commerce.iam.store-read.enabled", "commerce.iam.employee.enabled"}, havingValue = "true")
@RequestMapping("/v1/operations/member-growth")
public class GrowthActionsController {
    private final EmployeeAccess access;
    public GrowthActionsController(EmployeeAccess access) { this.access = access; }
    /** 发布新版本，提示只核对完整租户资格。 */
    @GetMapping("/policy-access")
    public ActionAccess policy(@AuthenticationPrincipal Actor actor) { return check(actor, EmployeeAccess.Capability.GROWTH_POLICY_PUBLISH); }
    /** 人工调整不默认授予重算或读取权限。 */
    @GetMapping("/adjust-access")
    public ActionAccess adjust(@AuthenticationPrincipal Actor actor) { return check(actor, EmployeeAccess.Capability.GROWTH_ADJUST); }
    /** 重算保持原业务流程，不在读取时改变等级。 */
    @GetMapping("/recalculate-access")
    public ActionAccess recalculate(@AuthenticationPrincipal Actor actor) { return check(actor, EmployeeAccess.Capability.GROWTH_RECALCULATE); }
    private ActionAccess check(Actor actor, EmployeeAccess.Capability capability) {
        if (actor == null || actor.role() != Actor.Role.OPERATOR || actor.executionId() == null)
            throw new DomainException(DomainException.Code.FORBIDDEN, "中央会员授权拒绝");
        var before = access.scope(actor, capability);
        EmployeeAccess.requireSame(before, access.scope(actor, capability));
        return new ActionAccess(true);
    }
    /** 提示不是可复用的授权许可。 */
    public record ActionAccess(boolean allowed) {}
}
