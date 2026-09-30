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
@RequestMapping("/v1/operations/members")
public class MemberActionsController {
    private final EmployeeAccess access;
    public MemberActionsController(EmployeeAccess access) { this.access = access; }
    /** 创建不存在对象，提示只核对完整租户资格。 */
    @GetMapping("/create-access")
    public ActionAccess create(@AuthenticationPrincipal Actor actor) { return check(actor, EmployeeAccess.Capability.MEMBER_CREATE); }
    /** 资料修改不默认授予状态修改或读权限。 */
    @GetMapping("/profile-access")
    public ActionAccess profile(@AuthenticationPrincipal Actor actor) { return check(actor, EmployeeAccess.Capability.MEMBER_PROFILE_UPDATE); }
    /** 生命周期变更保持原业务流程与终态约束。 */
    @GetMapping("/status-access")
    public ActionAccess status(@AuthenticationPrincipal Actor actor) { return check(actor, EmployeeAccess.Capability.MEMBER_STATUS_UPDATE); }
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
