package com.lrj.commerce.app.http.marketing.asset;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import com.lrj.commerce.runtime.api.identity.Actor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 人群创建提示独立于目录读取，不能作为可复用许可或导入成员有效性证明。 */
@RestController
@ConditionalOnProperty(name = {"commerce.iam.store-read.enabled", "commerce.iam.employee.enabled"}, havingValue = "true")
public class AudienceActionsController {
    private final EmployeeAccess access;
    public AudienceActionsController(EmployeeAccess access) { this.access = access; }

    /** 只返回当前完整租户创建资格；真实提交仍重新判权和检查原输入。 */
    @GetMapping("/v1/operations/audiences/create-access")
    public ActionAccess create(@AuthenticationPrincipal Actor actor) {
        if (actor == null || actor.role() != Actor.Role.OPERATOR || actor.executionId() == null)
            throw new DomainException(DomainException.Code.FORBIDDEN, "中央人群授权拒绝");
        var before = access.scope(actor, EmployeeAccess.Capability.AUDIENCE_CREATE);
        EmployeeAccess.requireSame(before, access.scope(actor, EmployeeAccess.Capability.AUDIENCE_CREATE));
        return new ActionAccess(true);
    }

    /** 提示不携带授权凭据，不隐含目录读取权限。 */
    public record ActionAccess(boolean allowed) {}
}
