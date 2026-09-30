package com.lrj.commerce.app.http.benefit.coupon;

import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.kernel.DomainException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 创建资格独立于目录读取；提示不代替命令提交时的实时权限和业务规则。 */
@RestController
@ConditionalOnProperty(name = {"commerce.iam.store-read.enabled", "commerce.iam.employee.enabled"}, havingValue = "true")
@RequestMapping("/v1/operations/coupon-definitions")
public class CouponDefinitionActionsController {
    private final EmployeeAccess access;
    public CouponDefinitionActionsController(EmployeeAccess access) { this.access = access; }
    /** 不构造尚不存在的券版本事实，只核对完整租户资格。 */
    @GetMapping("/create-access")
    public ActionAccess create(@AuthenticationPrincipal Actor actor) {
        if (actor == null || actor.role() != Actor.Role.OPERATOR || actor.executionId() == null)
            throw new DomainException(DomainException.Code.FORBIDDEN, "中央券定义授权拒绝");
        var before = access.scope(actor, EmployeeAccess.Capability.COUPON_DEFINITION_CREATE);
        EmployeeAccess.requireSame(before, access.scope(actor, EmployeeAccess.Capability.COUPON_DEFINITION_CREATE));
        return new ActionAccess(true);
    }
    /** 返回提示状态，不产生新的授权许可。 */
    public record ActionAccess(boolean allowed) {}
}
