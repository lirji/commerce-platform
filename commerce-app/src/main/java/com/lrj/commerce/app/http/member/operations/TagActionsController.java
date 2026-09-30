package com.lrj.commerce.app.http.member.operations;

import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.kernel.DomainException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 定义和分配资格分别查询，不隐含读取；目标会员仍在实际提交时由Owner核验。 */
@RestController
@ConditionalOnProperty(name = {"commerce.iam.store-read.enabled", "commerce.iam.employee.enabled"}, havingValue = "true")
@RequestMapping("/v1/operations/member-tags")
public class TagActionsController {
    private final EmployeeAccess access;
    public TagActionsController(EmployeeAccess access) { this.access = access; }
    /** 定义字典项的提示只核对完整租户资格。 */
    @GetMapping("/define-access")
    public ActionAccess define(@AuthenticationPrincipal Actor actor) { return check(actor, EmployeeAccess.Capability.MEMBER_TAG_DEFINE); }
    /** 分配与撤销不默认授予字典或会员标签读取。 */
    @GetMapping("/assign-access")
    public ActionAccess assign(@AuthenticationPrincipal Actor actor) { return check(actor, EmployeeAccess.Capability.MEMBER_TAG_ASSIGN); }
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
