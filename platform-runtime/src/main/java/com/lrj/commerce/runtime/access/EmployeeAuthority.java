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
    /** 创建与目录使用集合许可，不能用虚构门店通过旧单资源门禁。 */
    @Override public ScopePermit scope(Actor actor, Capability capability) {
        if (capability == Capability.INVENTORY_READ || capability == Capability.INVENTORY_RECEIVE) throw denied();
        Route route = routes.find(actor.tenantId(), capability.family());
        State state = route == null ? State.LEGACY : State.valueOf(route.state());
        Instant until = Instant.now().plusSeconds(5);
        if (state == State.LEGACY || state == State.SHADOW) {
            if (actor.executionId() != null) throw denied();
            actor.requireAdmin();
            var all = new com.lrj.commerce.runtime.api.scope.ScopeQuery.Filter(java.util.List.of(
                    new com.lrj.commerce.runtime.api.scope.ScopeQuery.Path(true,java.util.List.of(),java.util.List.of())));
            return new ScopePermit(capability, actor.tenantId(), all, route, null, "legacy", until);
        }
        if (state != State.CENTRAL || actor.role() != Actor.Role.OPERATOR || actor.executionId() == null) throw denied();
        var adapter = central.getIfAvailable(); if (adapter == null) throw denied();
        var decision = adapter.scope(actor, capability, route.authTenantId());
        if (decision.until().isBefore(until)) until = decision.until();
        if (!until.isAfter(Instant.now()) || decision.filter().paths().isEmpty()) throw denied();
        if ((capability == Capability.MERCHANT_CREATE || capability == Capability.STORE_CREATE || memberCapability(capability)
                || capability == Capability.GROWTH_POLICY_READ || capability == Capability.GROWTH_POLICY_PUBLISH)
                && decision.filter().paths().stream().noneMatch(com.lrj.commerce.runtime.api.scope.ScopeQuery.Path::tenantAll)) throw denied();
        return new ScopePermit(capability, actor.tenantId(), decision.filter(), route, decision.identity(), decision.fingerprint(), until);
    }
    /** 对象判权追加在可信集合许可上，仍限制相同路由、身份和准入时间。 */
    @Override public ResourcePermit resource(Actor actor, ScopePermit permit, ResourceFact fact) {
        if (!memberCapability(permit.capability()) || permit.capability() == Capability.MEMBER_CREATE
                || !actor.tenantId().equals(permit.tenant()) || fact == null
                || !permit.capability().resourceType().equals(fact.type()) || fact.version() < 0) throw denied();
        com.lrj.commerce.kernel.Identifiers.require(fact.id());
        if (!permit.until().isAfter(Instant.now()) || !Objects.equals(routes.find(permit.tenant(), permit.capability().family()), permit.route())) throw denied();
        if (permit.identity() == null) {
            actor.requireAdmin();
            if (actor.executionId() != null || (permit.route() != null && !java.util.Set.of(State.LEGACY.name(), State.SHADOW.name()).contains(permit.route().state()))) throw denied();
            return new ResourcePermit(permit, fact);
        }
        if (actor.role() != Actor.Role.OPERATOR || actor.executionId() == null) throw denied();
        var adapter = central.getIfAvailable(); if (adapter == null) throw denied();
        var decision = adapter.resource(actor, permit.capability(), fact, permit.route().authTenantId());
        Instant until = decision.until().isBefore(permit.until()) ? decision.until() : permit.until();
        if (!until.isAfter(Instant.now()) || !permit.identity().equals(decision.identity())
                || !Objects.equals(routes.find(permit.tenant(), permit.capability().family()), permit.route())) throw denied();
        return new ResourcePermit(new ScopePermit(permit.capability(), permit.tenant(), permit.filter(), permit.route(), permit.identity(), permit.fingerprint(), until), fact);
    }
    private static boolean memberCapability(Capability capability) {
        return java.util.Set.of(Capability.MEMBER_READ, Capability.MEMBER_CREATE, Capability.MEMBER_PROFILE_UPDATE, Capability.MEMBER_STATUS_UPDATE, Capability.GROWTH_READ, Capability.GROWTH_ADJUST, Capability.GROWTH_RECALCULATE).contains(capability);
    }
    /** 集合许可的路由锁先于命令回执与业务写入。 */
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void lock(ScopePermit permit) {
        if (!Objects.equals(routes.lock(permit.tenant(), permit.capability().family()), permit.route())
                || !permit.until().isAfter(Instant.now())) throw denied();
    }
    /** 目录效果与主体归属在原Commands事务中原子提交。 */
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void audit(Actor actor, ScopePermit permit, String operation, String key, String resourceId) {
        if (permit.identity() == null) return;
        com.lrj.commerce.kernel.Identifiers.require(resourceId);
        if (!actor.tenantId().equals(permit.tenant()) || routes.auditScoped(actor, permit, permit.capability().code(),
                permit.capability().resourceType(), operation, key, resourceId,
                permit.capability() == Capability.STORE_CREATE ? resourceId : null) != 1)
            throw new DomainException(DomainException.Code.CONFLICT, "中央目录操作归属记录失败");
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
