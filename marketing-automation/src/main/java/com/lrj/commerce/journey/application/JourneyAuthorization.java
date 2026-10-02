package com.lrj.commerce.journey.application;

import com.lrj.commerce.journey.api.JourneyApi.*;
import com.lrj.commerce.journey.infrastructure.persistence.JourneyMapper;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import com.lrj.commerce.runtime.api.access.EmployeeAccess.*;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import com.lrj.commerce.kernel.*;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Component;
import static com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.*;

/** 旅程Owner保存不可替换来源；远程授权在事务外，事务内只锁权威和真实固定内容。 */
@Component
public class JourneyAuthorization {
    enum Kind { MANUAL, SYSTEM, LEGACY }
    record Policy(String tenant, String journeyId, long contentVersion, Actor approvedBy, Route route, String key, Instant approvedAt) {}
    record Source(Kind kind, String tenant, String journeyId, long contentVersion, Actor actor, FiniteExecution execution, Policy policy, String key, Instant createdAt) {}
    record Gate(Source source, ScopePermit human, PolicyPermit policy) {}
    private final EmployeeAccess access;
    private final JourneyMapper mapper;
    public JourneyAuthorization(EmployeeAccess access, JourneyMapper mapper) { this.access=access;this.mapper=mapper; }
    /** 只从认证边界读取原引用，集合创建不能冒用待创建实例Facts。 */
    Source manual(Actor actor, ScopePermit permit, Definition definition, String key, Instant at) {
        if(permit.identity()==null) return new Source(Kind.LEGACY,actor.tenantId(),definition.journeyId(),definition.version(),actor,null,null,key,at);
        var original=access.execution(actor,JOURNEY_INSTANCE_CREATE);
        if(!Objects.equals(original.identity(),permit.identity()) || !Objects.equals(original.route(),permit.route()))throw denied();
        return new Source(Kind.MANUAL,actor.tenantId(),definition.journeyId(),definition.version(),actor,original,null,key,at);
    }
    /** publish的真实提交持有对象许可，政策归属内容版本，不借批准人的短引用长期执行。 */
    void publish(Actor actor, ScopePermit permit, Definition definition, String key, Instant at) {
        var policy=new Policy(actor.tenantId(),definition.journeyId(),definition.version(),actor,permit.route(),key,at);
        if(mapper.policy(actor.tenantId(),definition.journeyId(),definition.version())!=null)return;
        if(mapper.publishPolicy(actor.tenantId(),definition.journeyId(),definition.version(),JsonCodec.write(policy))!=1)throw conflict();
    }
    Source system(String tenant, Definition definition, String key, Instant at) {
        String json=mapper.policy(tenant,definition.journeyId(),definition.version());
        // 迁移前空值只能在从未中央接管的旧权威继续，不能补造系统政策。
        if(json==null) { access.policy(tenant,JOURNEY_PUBLISH,null);return new Source(Kind.LEGACY,tenant,definition.journeyId(),definition.version(),null,null,null,key,at); }
        var policy=JsonCodec.read(json,Policy.class);
        if(!tenant.equals(policy.tenant()) || !definition.journeyId().equals(policy.journeyId()) || definition.version()!=policy.contentVersion() || policy.approvedBy()==null)throw denied();
        return new Source(Kind.SYSTEM,tenant,definition.journeyId(),definition.version(),null,null,policy,key,at);
    }
    /** 每个节点重新相交原Grant与当前Grant；SYSTEM只按实际发布政策核验独立权威。 */
    Gate gate(String tenant, Source source) {
        if(source==null) return new Gate(null,null,access.policy(tenant,JOURNEY_PUBLISH,null));
        if(!tenant.equals(source.tenant()) || source.contentVersion()<=0)throw denied();
        if(source.kind()==Kind.MANUAL) {
            if(source.actor()==null || source.execution()==null || !source.execution().equals(access.execution(source.actor(),JOURNEY_INSTANCE_CREATE)))throw denied();
            var permit=access.scope(source.actor(),JOURNEY_INSTANCE_CREATE);
            if(!Objects.equals(permit.identity(),source.execution().identity()) || !Objects.equals(permit.route(),source.execution().route()))throw denied();
            return new Gate(source,permit,null);
        }
        if(source.kind()==Kind.SYSTEM) {
            var policy=source.policy();
            if(policy==null || !tenant.equals(policy.tenant()) || !source.journeyId().equals(policy.journeyId()) || source.contentVersion()!=policy.contentVersion())throw denied();
            return new Gate(source,null,access.policy(tenant,JOURNEY_PUBLISH,policy.route()));
        }
        return new Gate(source,null,access.policy(tenant,JOURNEY_PUBLISH,null));
    }
    Source source(String tenant, Instance instance) {
        String json=mapper.source(tenant,instance.instanceId());
        if(json==null)return null;
        var source=JsonCodec.read(json,Source.class);
        if(!tenant.equals(source.tenant()) || !instance.journeyId().equals(source.journeyId()) || instance.journeyVersion()!=source.contentVersion())throw denied();
        return source;
    }
    /** 路由锁先于业务行；提交前再次检查窗口，避免锁等待跨越准入。 */
    void lock(Gate gate) {
        if(gate.human()!=null)access.lock(gate.human());else access.lock(gate.policy());
        requireFresh(gate);
    }
    void requireFresh(Gate gate) {
        Instant until=gate.human()!=null?gate.human().until():gate.policy().until();
        if(!until.isAfter(Instant.now()) || (gate.source()!=null && gate.source().execution()!=null && !gate.source().execution().expiresAt().isAfter(Instant.now())))throw denied();
    }
    Object input(ScopePermit permit,Object input) { return permit.identity()==null?input:new Object[]{permit.identity(),input}; }
    ScopePermit scope(Actor actor, Capability capability) { return access.scope(actor,capability); }
    ResourcePermit object(Actor actor,Capability capability,String id,long contentVersion) {
        return access.resource(actor,access.scope(actor,capability),new ResourceFact(capability.resourceType(),id,contentVersion));
    }
    void lock(ScopePermit permit) { access.lock(permit); }
    void after(Actor actor,ScopePermit permit) { EmployeeAccess.requireSame(permit,access.scope(actor,permit.capability())); }
    void audit(Actor actor,ScopePermit permit,String operation,String key,String id,long contentVersion) { access.auditVersion(actor,permit,operation,key,id,contentVersion); }
    private static DomainException denied(){return new DomainException(DomainException.Code.FORBIDDEN,"旅程原来源或权威不允许继续");}
    private static DomainException conflict(){return new DomainException(DomainException.Code.CONFLICT,"旅程固定政策保存冲突");}
}
