package com.lrj.commerce.runtime.replay;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.replay.persistence.ReplayMapper;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Component;
import static com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.RUNTIME_REPLAY_CREATE;

/** 回放只有手工来源；系统调度不把旧 ADMIN 或新控制员工变成原任务的授权来源。 */
@Component
public class ReplayAuthorization {
    enum Kind { LEGACY, MANUAL }
    record Source(Kind kind, String tenant, String jobId, Actor actor,
                  EmployeeAccess.FiniteExecution execution, String key, Instant createdAt) {}
    record Gate(Source source, String json, EmployeeAccess.ScopePermit permit) {}
    private final EmployeeAccess access;
    private final ReplayMapper mapper;
    public ReplayAuthorization(EmployeeAccess access, ReplayMapper mapper) { this.access=access;this.mapper=mapper; }
    /** 只复制实际认证边界签发的原引用元数据，不保存 Token 或可复用 ALLOW。 */
    Source create(Actor actor, EmployeeAccess.ScopePermit permit, String job, String key, Instant now) {
        if(permit.identity()==null)return new Source(Kind.LEGACY,actor.tenantId(),job,actor,null,key,now);
        var original=access.execution(actor,RUNTIME_REPLAY_CREATE);
        if(!Objects.equals(permit.identity(),original.identity()) || !Objects.equals(permit.route(),original.route()))throw denied();
        return new Source(Kind.MANUAL,actor.tenantId(),job,actor,original,key,now);
    }
    /** 迁移已为旧任务记录真实 created_by 的 LEGACY 来源，缺失来源始终拒绝，不升级为 SYSTEM。 */
    Gate gate(String tenant, ReplayMapper.Job job) {
        String json=mapper.source(tenant,job.jobId());
        if(json==null)throw denied();
        var source=JsonCodec.read(json,Source.class);
        if(source.kind()==null || !tenant.equals(source.tenant()) || !job.jobId().equals(source.jobId())
                || source.actor()==null || !tenant.equals(source.actor().tenantId()) || !job.createdBy().equals(source.actor().actorId()))throw denied();
        if(source.kind()==Kind.MANUAL) {
            if(source.execution()==null || !source.execution().equals(access.execution(source.actor(),RUNTIME_REPLAY_CREATE)))throw denied();
        } else if(source.actor().role()!=Actor.Role.ADMIN || source.actor().executionId()!=null || source.execution()!=null)throw denied();
        var permit=access.scope(source.actor(),RUNTIME_REPLAY_CREATE);
        if(source.kind()==Kind.MANUAL && (!Objects.equals(permit.identity(),source.execution().identity())
                || !Objects.equals(permit.route(),source.execution().route())))throw denied();
        if(source.kind()==Kind.LEGACY && permit.identity()!=null)throw denied();
        var gate=new Gate(source,json,permit);fresh(gate);return gate;
    }
    /** 每项原引用远程重验在事务外；锁住路由和不可替换来源后再次核对期限。 */
    void lock(Gate gate) {
        access.lock(gate.permit());
        if(!Objects.equals(gate.json(),mapper.sourceLock(gate.source().tenant(),gate.source().jobId())))throw denied();
        fresh(gate);
    }
    void fresh(Gate gate) {
        if(!gate.permit().until().isAfter(Instant.now()) || (gate.source().execution()!=null
                && !gate.source().execution().expiresAt().isAfter(Instant.now())))throw denied();
    }
    private static DomainException denied(){return new DomainException(DomainException.Code.FORBIDDEN,"回放原员工来源或授权窗口不允许继续");}
}
