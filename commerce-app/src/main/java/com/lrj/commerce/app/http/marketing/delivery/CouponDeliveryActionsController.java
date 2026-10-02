package com.lrj.commerce.app.http.marketing.delivery;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import com.lrj.commerce.runtime.api.identity.Actor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 只提示本次独立操作资格，不创建批次、命令、审计或长任务来源。 */
@RestController
@ConditionalOnProperty(name = {"commerce.iam.store-read.enabled", "commerce.iam.employee.enabled"}, havingValue = "true")
public class CouponDeliveryActionsController {
    private final EmployeeAccess access;

    public CouponDeliveryActionsController(EmployeeAccess access) { this.access = access; }

    /** 创建岗位不需要额外批次、门店或券定义读取能力。 */
    @GetMapping("/v1/operations/coupon-deliveries/create-access")
    public ActionAccess create(@AuthenticationPrincipal Actor actor) {
        return qualified(actor, EmployeeAccess.Capability.COUPON_DELIVERY_CREATE);
    }

    /** 集合资格不替代POST中的真实目标、进度CAS及原方向来源检查。 */
    @GetMapping("/v1/operations/coupon-deliveries/control-access")
    public ActionAccess control(@AuthenticationPrincipal Actor actor) {
        return qualified(actor, EmployeeAccess.Capability.COUPON_DELIVERY_CONTROL);
    }

    /** 单次推进资格不能为已有批次换源或承诺任务全部完成。 */
    @GetMapping("/v1/operations/coupon-deliveries/pump-access")
    public ActionAccess pump(@AuthenticationPrincipal Actor actor) {
        return qualified(actor, EmployeeAccess.Capability.COUPON_DELIVERY_PUMP);
    }

    private ActionAccess qualified(Actor actor, EmployeeAccess.Capability capability) {
        if (actor == null || actor.role() != Actor.Role.OPERATOR || actor.executionId() == null)
            throw new DomainException(DomainException.Code.FORBIDDEN, "中央发券操作资格拒绝");
        var before = access.scope(actor, capability);
        EmployeeAccess.requireSame(before, access.scope(actor, capability));
        return new ActionAccess(true);
    }

    /** 提示无可复用授权，真正提交必须再次通过Owner校验。 */
    public record ActionAccess(boolean allowed) {}
}
