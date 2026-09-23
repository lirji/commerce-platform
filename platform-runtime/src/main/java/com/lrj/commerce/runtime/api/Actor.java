package com.lrj.commerce.runtime.api;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.kernel.Identifiers;

/** 由认证边界构造的业务身份，租户不能从请求体替换。 */
public record Actor(String tenantId, String actorId, Role role, Channel channel) {
    public enum Channel { WEB, MINI_APP }
    /** 旧调用和旧快照保留默认网站渠道。 */
    public Actor(String tenantId,String actorId,Role role){this(tenantId,actorId,role,Channel.WEB);}
    public enum Role { ADMIN, MEMBER, OPERATOR }
    public Actor {
        channel=channel==null?Channel.WEB:channel;
        Identifiers.require(tenantId); Identifiers.require(actorId);
        if (role == null) throw new DomainException(DomainException.Code.FORBIDDEN, "身份角色无效");
    }
    /** 管理权限仍在用例层校验，不能只靠控制器或页面隐藏入口。 */
    public void requireAdmin() {
        if (role != Role.ADMIN) throw new DomainException(DomainException.Code.FORBIDDEN, "需要管理权限");
    }
}
