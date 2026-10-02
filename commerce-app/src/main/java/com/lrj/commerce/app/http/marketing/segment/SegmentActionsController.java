package com.lrj.commerce.app.http.marketing.segment;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import com.lrj.commerce.runtime.api.identity.Actor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 动态人群的五项操作资格彼此独立；提示不创建命令、审计或可复用授权。 */
@RestController
@ConditionalOnProperty(name = {"commerce.iam.store-read.enabled", "commerce.iam.employee.enabled"}, havingValue = "true")
public class SegmentActionsController {
    private final EmployeeAccess access;

    public SegmentActionsController(EmployeeAccess access) { this.access = access; }

    /** 创建资格不要求读取目录。 */
    @GetMapping("/v1/operations/segments/create-access")
    public ActionAccess create(@AuthenticationPrincipal Actor actor) {
        return qualified(actor, EmployeeAccess.Capability.SEGMENT_CREATE);
    }

    /** 调度资格不替代真实目标的版本与范围检查。 */
    @GetMapping("/v1/operations/segments/schedule-access")
    public ActionAccess schedule(@AuthenticationPrincipal Actor actor) {
        return qualified(actor, EmployeeAccess.Capability.SEGMENT_SCHEDULE);
    }

    /** 刷新资格不代表原任务或输出快照已完成。 */
    @GetMapping("/v1/operations/segments/refresh-access")
    public ActionAccess refresh(@AuthenticationPrincipal Actor actor) {
        return qualified(actor, EmployeeAccess.Capability.SEGMENT_REFRESH);
    }

    /** 控制资格不能替代原任务来源的授权。 */
    @GetMapping("/v1/operations/segments/control-access")
    public ActionAccess control(@AuthenticationPrincipal Actor actor) {
        return qualified(actor, EmployeeAccess.Capability.SEGMENT_CONTROL);
    }

    /** 推进资格只允许一次有界调用，任务批次仍重新验证原来源。 */
    @GetMapping("/v1/operations/segments/pump-access")
    public ActionAccess pump(@AuthenticationPrincipal Actor actor) {
        return qualified(actor, EmployeeAccess.Capability.SEGMENT_PUMP);
    }

    /** 前后复核同一完整租户范围，阻止提示期间发生身份或授权范围变化。 */
    private ActionAccess qualified(Actor actor, EmployeeAccess.Capability capability) {
        if (actor == null || actor.role() != Actor.Role.OPERATOR || actor.executionId() == null)
            throw new DomainException(DomainException.Code.FORBIDDEN, "中央动态人群授权拒绝");
        var before = access.scope(actor, capability);
        EmployeeAccess.requireSame(before, access.scope(actor, capability));
        return new ActionAccess(true);
    }

    /** 只公开当前资格，实际操作必须再次鉴权。 */
    public record ActionAccess(boolean allowed) {}
}
