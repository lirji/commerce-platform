package com.lrj.commerce.app.iam;

import com.lrj.authz.protocol.CentralAccessDtos.Check;
import com.lrj.authz.protocol.ScopeDtos.Facts;
import com.lrj.authz.sdk.*;
import com.lrj.commerce.runtime.api.access.*;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.kernel.DomainException;
import java.time.Instant;
import java.util.*;

/** 中央员工只取得单一能力引用；既有OPERATOR身份本身不授予库存或ADMIN权限。 */
public final class CentralEmployeeService implements CentralEmployeeCheck {
    private static final String RESOURCE_TYPE = "store";
    private final CentralAccessClient client;
    private final CentralStoreBindingMapper bindings;
    private final EmployeeAccess access;
    private final com.lrj.commerce.store.management.api.StoreApi stores;
    public CentralEmployeeService(CentralAccessClient client, CentralStoreBindingMapper bindings, EmployeeAccess access, com.lrj.commerce.store.management.api.StoreApi stores) {
        this.client = client; this.bindings = bindings; this.access = access; this.stores = stores;
    }
    /** 引用有效期有界，用例仍逐次核对Owner资源及当前Grant。 */
    public Actor authenticate(String token, String tenant, EmployeeAccess.Capability capability) {
        var route = access.central(tenant, capability);
        if (route == null) throw new AccessDeniedException("CENTRAL_EMPLOYEE_DENIED");
        var ref = client.issueExecution(token, check(tenant, capability), Instant.now().plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        var c = ref.context();
        var actor = bindings.find(tenant, c.principalId(), c.membershipId(), c.membershipGeneration());
        if (actor == null || !route.tenantId().equals(actor.tenantId())) throw new AccessDeniedException("CENTRAL_EMPLOYEE_DENIED");
        return new Actor(actor.tenantId(), actor.actorId(), Actor.Role.OPERATOR, actor.channel(), ref.executionId());
    }
    /** 返回主体必须仍映射为原Actor；其他能力或代际的引用不能借用。 */
    @Override public Decision require(Actor actor, EmployeeAccess.Capability capability, EmployeeAccess.StoreFact fact, String tenant) {
        if (fact == null || actor.executionId() == null || actor.role() != Actor.Role.OPERATOR) throw denied();
        try {
            var result = client.checkExecution(actor.executionId(), check(tenant, capability),
                    new Facts(tenant, RESOURCE_TYPE, fact.id(), fact.version(), null, null, List.of(), fact.id(), null));
            if (!"ALLOW".equals(result.decision())) throw denied();
            var c = result.context();
            var current = bindings.find(tenant, c.principalId(), c.membershipId(), c.membershipGeneration());
            if (current == null || !current.tenantId().equals(actor.tenantId()) || !current.actorId().equals(actor.actorId())) throw denied();
            return new Decision(new EmployeeAccess.Identity(c.principalId(), c.membershipId(), c.membershipGeneration()), Instant.parse(result.validUntil()));
        } catch (CentralAccessException failure) {
            throw new DomainException(DomainException.Code.UNAVAILABLE, "中央员工授权暂不可用");
        } catch (AccessDeniedException failure) { throw denied(); }
    }
    /** 集合引用的范围由中央返回；SQL字段映射仅保留受支持的完整路径。 */
    @Override public ScopeDecision scope(Actor actor, EmployeeAccess.Capability capability, String tenant) {
        if (actor.executionId() == null || actor.role() != Actor.Role.OPERATOR) throw denied();
        try {
            var plan = client.executionScope(actor.executionId(), check(tenant, capability));
            if (!"ALLOW".equals(plan.decision())) throw denied();
            var c = plan.context();
            var current = bindings.find(tenant, c.principalId(), c.membershipId(), c.membershipGeneration());
            if (current == null || !current.tenantId().equals(actor.tenantId()) || !current.actorId().equals(actor.actorId())) throw denied();
            var paths = plan.alternatives().stream().map(a -> {
                boolean all = false; List<String> storeIds = List.of(), resources = List.of();
                for (var clause : a.clauses()) {
                    switch (clause.kind()) {
                        case TENANT_ALL -> all = true;
                        case SPECIFIED_STORES -> storeIds = clause.values();
                        case SPECIFIED_RESOURCES -> resources = clause.values();
                        default -> throw denied();
                    }
                }
                return new com.lrj.commerce.runtime.api.scope.ScopeQuery.Path(all, storeIds, resources);
            }).toList();
            return new ScopeDecision(new EmployeeAccess.Identity(c.principalId(), c.membershipId(), c.membershipGeneration()),
                    Instant.parse(plan.validUntil()), new com.lrj.commerce.runtime.api.scope.ScopeQuery.Filter(paths), CentralAccessClient.scopeFingerprint(plan));
        } catch (CentralAccessException failure) {
            throw new DomainException(DomainException.Code.UNAVAILABLE, "中央目录授权暂不可用");
        } catch (AccessDeniedException failure) { throw denied(); }
    }
    /** 业务Owner先读实际记录；适配层只转换事实并核对原Actor身份。 */
    @Override public Decision resource(Actor actor, EmployeeAccess.Capability capability, EmployeeAccess.ResourceFact fact, String tenant) {
        if (fact == null || !capability.resourceType().equals(fact.type()) || actor.executionId() == null || actor.role() != Actor.Role.OPERATOR) throw denied();
        try {
            var result = client.checkExecution(actor.executionId(), check(tenant, capability),
                    new Facts(tenant, fact.type(), fact.id(), fact.version(), null, null, List.of(), null, null));
            if (!"ALLOW".equals(result.decision())) throw denied();
            var c = result.context();
            var current = bindings.find(tenant, c.principalId(), c.membershipId(), c.membershipGeneration());
            if (current == null || !current.tenantId().equals(actor.tenantId()) || !current.actorId().equals(actor.actorId())) throw denied();
            return new Decision(new EmployeeAccess.Identity(c.principalId(), c.membershipId(), c.membershipGeneration()), Instant.parse(result.validUntil()));
        } catch (CentralAccessException failure) {
            throw new DomainException(DomainException.Code.UNAVAILABLE, "中央会员授权暂不可用");
        } catch (AccessDeniedException failure) { throw denied(); }
    }
    /** 提示必须独立检查写能力；再次读取Owner与read防止身份或范围切换后返回旧动作。 */
    public InventoryActions actions(Actor actor, String token, String tenant, String storeId) {
        if (actor.executionId() == null || actor.role() != Actor.Role.OPERATOR) throw denied();
        com.lrj.commerce.kernel.Identifiers.require(storeId);
        var store = store(actor, storeId);
        var fact = new EmployeeAccess.StoreFact(store.storeId(), store.version());
        var before = access.require(actor, EmployeeAccess.Capability.INVENTORY_READ, fact);
        boolean receive = false;
        try {
            var result = client.checkResource(token, check(tenant, EmployeeAccess.Capability.INVENTORY_RECEIVE),
                    new Facts(tenant, RESOURCE_TYPE, fact.id(), fact.version(), null, null, List.of(), fact.id(), null));
            var c = result.context();
            var identity = new EmployeeAccess.Identity(c.principalId(), c.membershipId(), c.membershipGeneration());
            receive = "ALLOW".equals(result.decision()) && identity.equals(before.identity());
        } catch (AccessDeniedException denied) { /* 明确无写能力只隐藏按钮，依赖故障继续上抛。 */ }
        if (!store.equals(store(actor, storeId))) throw denied();
        var after = access.require(actor, EmployeeAccess.Capability.INVENTORY_READ, fact);
        if (!Objects.equals(before.route(), after.route()) || !Objects.equals(before.identity(), after.identity())) throw denied();
        return new InventoryActions(receive);
    }
    /** 仅返回最小动作提示，不暴露Grant与员工目录。 */
    public record InventoryActions(boolean receive) {}
    private com.lrj.commerce.store.management.api.StoreApi.View store(Actor actor, String storeId) {
        try { return stores.requireActive(actor, storeId); }
        catch (DomainException failure) {
            if (failure.code() == DomainException.Code.NOT_FOUND) throw denied();
            throw failure;
        }
    }
    private Check check(String tenant, EmployeeAccess.Capability capability) {
        return new Check(tenant, null, UUID.randomUUID().toString(), capability.code(), capability.resourceType());
    }
    private static DomainException denied() {
        return new DomainException(DomainException.Code.FORBIDDEN, "中央员工授权拒绝");
    }
}
