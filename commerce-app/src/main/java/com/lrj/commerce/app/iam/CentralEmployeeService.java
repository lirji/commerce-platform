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
    public CentralEmployeeService(CentralAccessClient client, CentralStoreBindingMapper bindings, EmployeeAccess access) {
        this.client = client; this.bindings = bindings; this.access = access;
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
    private Check check(String tenant, EmployeeAccess.Capability capability) {
        return new Check(tenant, null, UUID.randomUUID().toString(), capability.code(), RESOURCE_TYPE);
    }
    private static DomainException denied() {
        return new DomainException(DomainException.Code.FORBIDDEN, "中央员工授权拒绝");
    }
}
