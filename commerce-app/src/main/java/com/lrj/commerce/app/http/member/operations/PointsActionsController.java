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
@RequestMapping("/v1/operations/member-points")
public class PointsActionsController {
    private final EmployeeAccess access;
    public PointsActionsController(EmployeeAccess access) { this.access = access; }
    /** 发布新版本，提示只核对完整租户资格。 */
    @GetMapping("/policy-access")
    public ActionAccess policy(@AuthenticationPrincipal Actor actor) { return check(actor, EmployeeAccess.Capability.POINTS_POLICY_PUBLISH); }
    /** 人工调整不默认授予到期或读取权限。 */
    @GetMapping("/adjust-access")
    public ActionAccess adjust(@AuthenticationPrincipal Actor actor) { return check(actor, EmployeeAccess.Capability.POINTS_ADJUST); }
    /** 到期推进保持原批次上限，不在读取时归档批次。 */
    @GetMapping("/expire-access")
    public ActionAccess expire(@AuthenticationPrincipal Actor actor) { return check(actor, EmployeeAccess.Capability.POINTS_EXPIRE); }
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
