package com.lrj.commerce.app.http.benefit.entitlement;

import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.kernel.DomainException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 独立动作提示不附赠读取权限，实际提交仍核对当前授权和真实业务事实。 */
@RestController
@ConditionalOnProperty(name = {"commerce.iam.store-read.enabled", "commerce.iam.employee.enabled"}, havingValue = "true")
public class EntitlementActionsController {
    private final EmployeeAccess access;
    public EntitlementActionsController(EmployeeAccess access) { this.access = access; }
    /** 创建只提示完整租户资格，不构造尚不存在的定义。 */
    @GetMapping("/v1/operations/entitlement-definitions/create-access")
    public ActionAccess create(@AuthenticationPrincipal Actor actor) {
        return hint(actor, EmployeeAccess.Capability.ENTITLEMENT_DEFINITION_CREATE);
    }
    /** 补偿提示不是实例许可，提交仍绑定真实grantId/version。 */
    @GetMapping("/v1/operations/entitlements/resolve-access")
    public ActionAccess resolve(@AuthenticationPrincipal Actor actor) {
        return hint(actor, EmployeeAccess.Capability.ENTITLEMENT_RESOLVE);
    }
    private ActionAccess hint(Actor actor, EmployeeAccess.Capability capability) {
        if (actor == null || actor.role() != Actor.Role.OPERATOR || actor.executionId() == null)
            throw new DomainException(DomainException.Code.FORBIDDEN, "中央权益授权拒绝");
        var before = access.scope(actor, capability);
        EmployeeAccess.requireSame(before, access.scope(actor, capability));
        return new ActionAccess(true);
    }
    /** 提示不携带可复用的授权凭据。 */
    public record ActionAccess(boolean allowed) {}
}
