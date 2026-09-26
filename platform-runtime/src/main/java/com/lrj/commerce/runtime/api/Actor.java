package com.lrj.commerce.runtime.api;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.kernel.Identifiers;

/** 由认证边界构造的业务身份，租户不能从请求体替换。 */
public record Actor(String tenantId, String actorId, Role role, Channel channel) {
    public enum Channel { WEB, MINI_APP }
    /** 旧调用和旧快照保留默认网站渠道。 */
    public Actor(String tenantId,String actorId,Role role){this(tenantId,actorId,role,Channel.WEB);}
    /** PLATFORM_OPERATOR只读取跨租户聚合运行指标，不是任何租户的管理员或会员；其tenantId只是凭据归属，平台接口不按它取数。 */
    public enum Role { ADMIN, MEMBER, OPERATOR, PLATFORM_OPERATOR }
    /** 平台能力按最小权限单独命名，不复用租户管理权限。 */
    public enum Capability { EVENT_RUNTIME_METRICS_READ }
    public Actor {
        channel=channel==null?Channel.WEB:channel;
        Identifiers.require(tenantId); Identifiers.require(actorId);
        if (role == null) throw new DomainException(DomainException.Code.FORBIDDEN, "身份角色无效");
    }
    /** 只有平台运维角色拥有平台能力；租户管理员不隐含任何跨租户能力。 */
    public java.util.Set<Capability> capabilities() {
        return role == Role.PLATFORM_OPERATOR ? java.util.EnumSet.of(Capability.EVENT_RUNTIME_METRICS_READ) : java.util.EnumSet.noneOf(Capability.class);
    }
    public void require(Capability capability) {
        if (!capabilities().contains(capability)) throw new DomainException(DomainException.Code.FORBIDDEN, "需要平台运维能力");
    }
    /** 管理权限仍在用例层校验，不能只靠控制器或页面隐藏入口。 */
    public void requireAdmin() {
        if (role != Role.ADMIN) throw new DomainException(DomainException.Code.FORBIDDEN, "需要管理权限");
    }
}
