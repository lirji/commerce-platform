package com.lrj.commerce.app.http.marketing.asset;

import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.kernel.DomainException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 独立动作提示不附赠读取权限，实际提交仍核对当前授权和真实业务事实。 */
@RestController
@ConditionalOnProperty(name = {"commerce.iam.store-read.enabled", "commerce.iam.employee.enabled"}, havingValue = "true")
public class RuleActionsController {
    private final EmployeeAccess access;
    public RuleActionsController(EmployeeAccess access) { this.access = access; }
    /** 创建只提示完整租户资格，不构造尚不存在的规则。 */
    @GetMapping("/v1/operations/rules/create-access")
    public ActionAccess create(@AuthenticationPrincipal Actor actor) {
        return hint(actor, EmployeeAccess.Capability.RULE_CREATE);
    }
    /** 发布提示不是规则许可，提交仍绑定真实ruleId和资产version。 */
    @GetMapping("/v1/operations/rules/publish-access")
    public ActionAccess publish(@AuthenticationPrincipal Actor actor) {
        return hint(actor, EmployeeAccess.Capability.RULE_PUBLISH);
    }
    private ActionAccess hint(Actor actor, EmployeeAccess.Capability capability) {
        if (actor == null || actor.role() != Actor.Role.OPERATOR || actor.executionId() == null)
            throw new DomainException(DomainException.Code.FORBIDDEN, "中央规则授权拒绝");
        var before = access.scope(actor, capability);
        EmployeeAccess.requireSame(before, access.scope(actor, capability));
        return new ActionAccess(true);
    }
    /** 提示不携带可复用的授权凭据。 */
    public record ActionAccess(boolean allowed) {}
}
