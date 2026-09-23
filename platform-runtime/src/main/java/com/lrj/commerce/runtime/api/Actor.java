package com.lrj.commerce.runtime.api;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.kernel.Identifiers;

/** 由认证边界构造的业务身份，租户不能从请求体替换。 */
public record Actor(String tenantId, String actorId, Role role) {
    public enum Role { ADMIN, MEMBER }
    public Actor {
        Identifiers.require(tenantId); Identifiers.require(actorId);
        if (role == null) throw new DomainException(DomainException.Code.FORBIDDEN, "身份角色无效");
    }
    /** 管理权限仍在用例层校验，不能只靠控制器或页面隐藏入口。 */
    public void requireAdmin() {
        if (role != Role.ADMIN) throw new DomainException(DomainException.Code.FORBIDDEN, "需要管理权限");
    }
}
