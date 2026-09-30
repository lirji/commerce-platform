package com.lrj.commerce.app.http.commerce;

import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.kernel.DomainException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 独立创建资格提示，不借用列表权限；提交时必须重新判权。 */
@RestController
@ConditionalOnProperty(name = {"commerce.iam.store-read.enabled", "commerce.iam.employee.enabled"}, havingValue = "true")
@RequestMapping("/v1/operations/directory")
public class DirectoryActionsController {
    private final EmployeeAccess access;
    public DirectoryActionsController(EmployeeAccess access) { this.access = access; }
    /** 商家创建只接受完整租户范围，没有待创建对象事实。 */
    @GetMapping("/merchants/create-access")
    public CreationAccess merchant(@AuthenticationPrincipal Actor actor) {
        return check(actor, EmployeeAccess.Capability.MERCHANT_CREATE);
    }
    /** 门店创建不隐含商家读取或库存能力。 */
    @GetMapping("/stores/create-access")
    public CreationAccess store(@AuthenticationPrincipal Actor actor) {
        return check(actor, EmployeeAccess.Capability.STORE_CREATE);
    }
    private CreationAccess check(Actor actor, EmployeeAccess.Capability capability) {
        if (actor == null || actor.role() != Actor.Role.OPERATOR || actor.executionId() == null)
            throw new DomainException(DomainException.Code.FORBIDDEN, "中央目录授权拒绝");
        var before = access.scope(actor, capability);
        EmployeeAccess.requireSame(before, access.scope(actor, capability));
        return new CreationAccess(true);
    }
    /** 有效只代表当前提示，不是可复用的业务写许可。 */
    public record CreationAccess(boolean allowed) {}
}
