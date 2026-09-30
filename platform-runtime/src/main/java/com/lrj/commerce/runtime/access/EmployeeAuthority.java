package com.lrj.commerce.runtime.access;

import com.lrj.commerce.runtime.api.access.*;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.access.persistence.EmployeeAuthorityMapper;
import com.lrj.commerce.kernel.DomainException;
import java.time.Instant;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

/** 每个能力族只接受一个持久权威；中央停用或故障不能恢复旧ADMIN。 */
@Component
public class EmployeeAuthority implements EmployeeAccess {
    private enum State { LEGACY, SHADOW, CENTRAL, STOPPED }
    private final EmployeeAuthorityMapper routes;
    private final ObjectProvider<CentralEmployeeCheck> central;
    public EmployeeAuthority(EmployeeAuthorityMapper routes, ObjectProvider<CentralEmployeeCheck> central) {
        this.routes = routes; this.central = central;
    }
    @Override public Route central(String authTenant, Capability capability) {
        return routes.central(authTenant, capability.family());
    }
    /** 远程检查发生在库存事务外，许可最多进入五秒内的本地提交窗口。 */
    @Override public Permit require(Actor actor, Capability capability, StoreFact fact) {
        Route route = routes.find(actor.tenantId(), capability.family());
        State state = route == null ? State.LEGACY : State.valueOf(route.state());
        Instant until = Instant.now().plusSeconds(5);
        if (state == State.LEGACY || state == State.SHADOW) {
            if (actor.executionId() != null) throw denied();
            actor.requireAdmin();
            return new Permit(capability, actor.tenantId(), fact, route, null, until);
        }
        if (state != State.CENTRAL || actor.role() != Actor.Role.OPERATOR || actor.executionId() == null) throw denied();
        var adapter = central.getIfAvailable();
        if (adapter == null) throw denied();
        var decision = adapter.require(actor, capability, fact, route.authTenantId());
        if (decision.until().isBefore(until)) until = decision.until();
        if (!until.isAfter(Instant.now())) throw denied();
        return new Permit(capability, actor.tenantId(), fact, route, decision.identity(), until);
    }
    /** 共享行锁与迁移CAS串行；即使命令已有回执也不能越过切换/停止。 */
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void lock(Permit permit) {
        var current = routes.lock(permit.tenant(), permit.capability().family());
        if (!Objects.equals(current, permit.route()) || !permit.until().isAfter(Instant.now())) throw denied();
    }
    /** 审计引用和业务效果同时提交，不把Token或可复用旧凭据写进库。 */
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void audit(Actor actor, Permit permit, String operation, String key) {
        if (permit.identity() == null) return;
        if (!actor.tenantId().equals(permit.tenant()) || routes.audit(actor, permit, permit.capability().code(), operation, key) != 1)
            throw new DomainException(DomainException.Code.CONFLICT, "中央操作归属记录失败");
    }
    private static DomainException denied() {
        return new DomainException(DomainException.Code.FORBIDDEN, "员工能力权威不允许该入口");
    }
}
