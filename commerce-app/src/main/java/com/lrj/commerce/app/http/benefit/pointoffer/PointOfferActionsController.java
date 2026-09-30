package com.lrj.commerce.app.http.benefit.pointoffer;

import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.kernel.DomainException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 两种写入资格分别查询，不隐含目录读取；实际商品事实只在提交时核验。 */
@RestController
@ConditionalOnProperty(name = {"commerce.iam.store-read.enabled", "commerce.iam.employee.enabled"}, havingValue = "true")
@RequestMapping("/v1/operations/point-offers")
public class PointOfferActionsController {
    private final EmployeeAccess access;
    public PointOfferActionsController(EmployeeAccess access) { this.access = access; }
    /** 定义未来商品只核对完整租户资格。 */
    @GetMapping("/define-access")
    public ActionAccess define(@AuthenticationPrincipal Actor actor) { return check(actor, EmployeeAccess.Capability.POINT_OFFER_DEFINE); }
    /** 停启不默认授予读取或新建权限。 */
    @GetMapping("/status-access")
    public ActionAccess status(@AuthenticationPrincipal Actor actor) { return check(actor, EmployeeAccess.Capability.POINT_OFFER_STATUS_UPDATE); }
    private ActionAccess check(Actor actor, EmployeeAccess.Capability capability) {
        if (actor == null || actor.role() != Actor.Role.OPERATOR || actor.executionId() == null)
            throw new DomainException(DomainException.Code.FORBIDDEN, "中央积分商品授权拒绝");
        var before = access.scope(actor, capability);
        EmployeeAccess.requireSame(before, access.scope(actor, capability));
        return new ActionAccess(true);
    }
    /** 提示不是可复用的授权许可。 */
    public record ActionAccess(boolean allowed) {}
}
