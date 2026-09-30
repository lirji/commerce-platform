package com.lrj.commerce.app.http.member.operations;

import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.kernel.DomainException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 偏好修改和重建分别授权，不隐含读取；实际提交时仍检查对应Owner。 */
@RestController
@ConditionalOnProperty(name = {"commerce.iam.store-read.enabled", "commerce.iam.employee.enabled"}, havingValue = "true")
@RequestMapping("/v1/operations/member-behavior")
public class BehaviorActionsController {
    private final EmployeeAccess access;
    public BehaviorActionsController(EmployeeAccess access) { this.access = access; }
    /** 修改偏好的提示只核对完整租户资格。 */
    @GetMapping("/update-access")
    public ActionAccess update(@AuthenticationPrincipal Actor actor) { return check(actor, EmployeeAccess.Capability.MEMBER_BEHAVIOR_UPDATE); }
    /** 重建提示不授予行为读取或订单查询。 */
    @GetMapping("/rebuild-access")
    public ActionAccess rebuild(@AuthenticationPrincipal Actor actor) { return check(actor, EmployeeAccess.Capability.MEMBER_BEHAVIOR_REBUILD); }
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
