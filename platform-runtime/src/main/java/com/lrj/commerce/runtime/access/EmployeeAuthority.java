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
            if (actor.executionId() != null || (newStoreCapability(capability) && route != null && route.everCentral())) throw denied();
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
            if (actor.executionId() != null || ((segmentCapability(capability) || couponDeliveryCapability(capability) || newTenantCapability(capability) || newStoreCapability(capability)) && route != null && route.everCentral())) throw denied();
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
                || pointOfferCapability(capability) || couponDefinitionCapability(capability) || entitlementCapability(capability) || ruleCapability(capability) || audienceCapability(capability) || campaignCapability(capability) || segmentCapability(capability) || couponDeliveryCapability(capability) || newTenantCapability(capability) || Capability.GROWTH_POLICY_READ.resourceType().equals(capability.resourceType()))
                && decision.filter().paths().stream().noneMatch(com.lrj.commerce.runtime.api.scope.ScopeQuery.Path::tenantAll)) throw denied();
        return new ScopePermit(capability, actor.tenantId(), decision.filter(), route, decision.identity(), decision.fingerprint(), until);
    }
    /** 对象判权追加在可信集合许可上，仍限制相同路由、身份和准入时间。 */
    @Override public ResourcePermit resource(Actor actor, ScopePermit permit, ResourceFact fact) {
        if ((!memberCapability(permit.capability()) && !pointOfferCapability(permit.capability())
                && permit.capability() != Capability.ENTITLEMENT_READ && permit.capability() != Capability.ENTITLEMENT_RESOLVE
                && permit.capability() != Capability.RULE_READ && permit.capability() != Capability.RULE_PUBLISH
                && !campaignAction(permit.capability()) && !segmentObject(permit.capability()) && !couponDeliveryObject(permit.capability()) && !newTenantObject(permit.capability()) && !runtimeObject(permit.capability())) || permit.capability() == Capability.MEMBER_CREATE || permit.capability() == Capability.MEMBER_TAG_DEFINE || permit.capability() == Capability.MEMBER_BEHAVIOR_REBUILD || permit.capability() == Capability.POINT_OFFER_DEFINE
                || !actor.tenantId().equals(permit.tenant()) || fact == null
                || !permit.capability().resourceType().equals(fact.type()) || fact.version() < 0
                || ((campaignAction(permit.capability()) || segmentObject(permit.capability()) || couponDeliveryObject(permit.capability()) || newTenantObject(permit.capability())) && fact.version() == 0)) throw denied();
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
    /** 积分商品采用租户集合，只有已有商品读取/停启可追加真实对象事实。 */
    private static boolean pointOfferCapability(Capability capability) {
        return java.util.Set.of(Capability.POINT_OFFER_READ, Capability.POINT_OFFER_DEFINE, Capability.POINT_OFFER_STATUS_UPDATE).contains(capability);
    }
    /** 券定义只支持完整租户的目录/创建集合，不能拼接指定门店或会员范围。 */
    private static boolean couponDefinitionCapability(Capability capability) {
        return capability == Capability.COUPON_DEFINITION_READ || capability == Capability.COUPON_DEFINITION_CREATE;
    }
    /** 定义和实例均完整租户范围，但只有实例允许追加实际授予事实。 */
    private static boolean entitlementCapability(Capability capability) {
        return java.util.Set.of(Capability.ENTITLEMENT_DEFINITION_READ, Capability.ENTITLEMENT_DEFINITION_CREATE,
                Capability.ENTITLEMENT_READ, Capability.ENTITLEMENT_RESOLVE).contains(capability);
    }
    /** 规则首批只按完整租户接管，创建不能冒用已有版本事实。 */
    private static boolean ruleCapability(Capability capability) {
        return java.util.Set.of(Capability.RULE_READ, Capability.RULE_CREATE, Capability.RULE_PUBLISH).contains(capability);
    }
    /** 人群目录与导入只支持完整租户集合，不暴露成员明细或单资源员工入口。 */
    private static boolean audienceCapability(Capability capability) {
        return capability == Capability.AUDIENCE_READ || capability == Capability.AUDIENCE_CREATE;
    }
    /** 活动和预算只允许完整租户集合，有限能力登记避免任意族字符串扩权。 */
    private static boolean campaignCapability(Capability capability) {
        return capability == Capability.CAMPAIGN_READ || capability == Capability.CAMPAIGN_CREATE
                || capability == Capability.BUDGET_READ || campaignAction(capability);
    }
    /** 只有已有版本动作追加对象判权，集合读和创建不能冒用对象事实。 */
    private static boolean campaignAction(Capability capability) {
        return java.util.Set.of(Capability.CAMPAIGN_PREVIEW, Capability.CAMPAIGN_SUBMIT, Capability.CAMPAIGN_APPROVE,
                Capability.CAMPAIGN_REJECT, Capability.CAMPAIGN_PUBLISH, Capability.CAMPAIGN_PAUSE).contains(capability);
    }
    /** 动态定义与快照/任务分开；集合创建及推进不能冒用已有对象。 */
    private static boolean segmentObject(Capability capability) {
        return java.util.Set.of(Capability.SEGMENT_READ, Capability.SEGMENT_SCHEDULE,
                Capability.SEGMENT_REFRESH, Capability.SEGMENT_CONTROL).contains(capability);
    }
    private static boolean segmentCapability(Capability capability) {
        return segmentObject(capability) || capability == Capability.SEGMENT_CREATE || capability == Capability.SEGMENT_PUMP;
    }
    /** 发券创建和人工推进仅集合，读回执与控制追加真实批次事实。 */
    private static boolean couponDeliveryObject(Capability capability) {
        return capability == Capability.COUPON_DELIVERY_READ || capability == Capability.COUPON_DELIVERY_CONTROL;
    }
    private static boolean couponDeliveryCapability(Capability capability) {
        return couponDeliveryObject(capability) || couponDeliveryDurable(capability) || capability == Capability.COUPON_DELIVERY_PUMP;
    }
    private static boolean couponDeliveryDurable(Capability capability) {
        return capability == Capability.COUPON_DELIVERY_CREATE || capability == Capability.COUPON_DELIVERY_CONTROL;
    }
    /** 新接管租户资源仍完整租户；门店仅为业务查询条件。 */
    private static boolean newTenantCapability(Capability cap) {
        return java.util.Set.of("JOURNEY", "MARKETING_REPORT", "OPS_PAGE", "EVENT", "RUNTIME", "DASHBOARD").contains(cap.family());
    }
    private static boolean newStoreCapability(Capability cap) {
        return java.util.Set.of("ORDER", "PAYMENT", "FULFILLMENT", "AFTERSALE", "REFUND").contains(cap.family());
    }
    /** 只有实际内容对象允许追加Facts；集合创建/校验/目录/报告不伪造对象。 */
    private static boolean newTenantObject(Capability cap) {
        return java.util.Set.of(Capability.JOURNEY_PREVIEW, Capability.JOURNEY_SUBMIT, Capability.JOURNEY_APPROVE,
                Capability.JOURNEY_REJECT, Capability.JOURNEY_PUBLISH, Capability.JOURNEY_PAUSE,
                Capability.JOURNEY_INSTANCE_READ, Capability.JOURNEY_INSTANCE_CONTROL, Capability.JOURNEY_SCAN_RETRY,
                Capability.OPS_PAGE_READ, Capability.OPS_PAGE_PREVIEW, Capability.OPS_PAGE_SUBMIT, Capability.OPS_PAGE_APPROVE,
                Capability.OPS_PAGE_REJECT, Capability.OPS_PAGE_PUBLISH, Capability.OPS_PAGE_PAUSE,
                Capability.OPS_PAGE_ROLLBACK, Capability.OPS_PAGE_EXECUTE).contains(cap);
    }
    /** 运行时对象由真实事件/恢复/重放Owner给出；没有内容正版本声明时保留原事实版本。 */
    private static boolean runtimeObject(Capability cap) {
        return java.util.Set.of(Capability.EVENT_READ,Capability.EVENT_RETRY,Capability.RUNTIME_READ,Capability.RUNTIME_RECOVER,Capability.RUNTIME_REPLAY_CONTROL).contains(cap);
    }
    /** 只有本片明确登记的长期任务能力保存来源，其余同步请求没有持久执行权。 */
    @Override @Transactional
    public void rememberExecution(Actor actor, Capability capability, FiniteExecution source) {
        if (!java.util.Set.of(Capability.JOURNEY_INSTANCE_CREATE,Capability.RUNTIME_REPLAY_CREATE).contains(capability) || actor.role()!=Actor.Role.OPERATOR || actor.executionId()==null
                || source==null || source.identity()==null || source.route()==null || source.expiresAt()==null
                || !actor.tenantId().equals(source.route().tenantId()) || !capability.family().equals(source.route().family())
                || !State.CENTRAL.name().equals(source.route().state()) || !source.expiresAt().isAfter(Instant.now())
                || source.expiresAt().isAfter(Instant.now().plusSeconds(capability.referenceSeconds()))
                || !Objects.equals(routes.lock(actor.tenantId(),capability.family()),source.route())) throw denied();
        if(routes.rememberExecution(actor,capability.code(),com.lrj.commerce.runtime.serialization.JsonCodec.write(source))!=1)
            throw new DomainException(DomainException.Code.CONFLICT,"原有限执行来源保存失败");
    }
    /** 精确Actor/引用/能力读取元数据，到期不能借重新登录续期。 */
    @Override public FiniteExecution execution(Actor actor, Capability capability) {
        if(!java.util.Set.of(Capability.JOURNEY_INSTANCE_CREATE,Capability.RUNTIME_REPLAY_CREATE).contains(capability) || actor.role()!=Actor.Role.OPERATOR || actor.executionId()==null) throw denied();
        var json=routes.execution(actor,capability.code());if(json==null)throw denied();
        var source=com.lrj.commerce.runtime.serialization.JsonCodec.read(json,FiniteExecution.class);
        if(!actor.tenantId().equals(source.route().tenantId()) || !source.expiresAt().isAfter(Instant.now()))throw denied();
        return source;
    }
    /** 已提交自动旅程政策不借发布人Grant；停止权威仍立即阻断未准入节点。 */
    @Override public PolicyPermit policy(String tenant, Capability capability, Route approvedRoute) {
        if(capability!=Capability.JOURNEY_PUBLISH)throw denied();
        var route=routes.find(tenant,capability.family());
        if(approvedRoute==null) {
            if(route!=null && (route.everCentral() || !java.util.Set.of(State.LEGACY.name(),State.SHADOW.name()).contains(route.state())))throw denied();
        } else if(route==null || !State.CENTRAL.name().equals(route.state()) || !route.everCentral()
                || !State.CENTRAL.name().equals(approvedRoute.state()) || !capability.family().equals(approvedRoute.family())
                || !tenant.equals(approvedRoute.tenantId()) || !Objects.equals(route.authTenantId(),approvedRoute.authTenantId())
                || route.version()<approvedRoute.version())throw denied();
        return new PolicyPermit(tenant,capability,route,Instant.now().plusSeconds(5));
    }
    /** 事务内权威共享锁先于业务行，截止不跨越真实Owner锁等待。 */
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void lock(PolicyPermit permit) {
        if(!Objects.equals(routes.lock(permit.tenant(),permit.capability().family()),permit.route()) || !permit.until().isAfter(Instant.now()))throw denied();
    }
    /** 签发结果来自中央适配层；写入前锁定对应中央路由，不把Token或ALLOW缓存持久化。 */
    @Override @Transactional
    public void rememberSegmentExecution(Actor actor, SegmentExecution source) {
        if (actor.role() != Actor.Role.OPERATOR || actor.executionId() == null || source == null
                || source.identity() == null || source.route() == null || !Capability.SEGMENT_REFRESH.family().equals(source.route().family())
                || !State.CENTRAL.name().equals(source.route().state()) || !actor.tenantId().equals(source.route().tenantId())
                || !source.expiresAt().isAfter(Instant.now()) || source.expiresAt().isAfter(Instant.now().plusSeconds(SEGMENT_REFRESH_REFERENCE_SECONDS))
                || !Objects.equals(routes.lock(actor.tenantId(), Capability.SEGMENT_REFRESH.family()), source.route())) throw denied();
        if (routes.rememberSegmentExecution(actor, com.lrj.commerce.runtime.serialization.JsonCodec.write(source)) != 1)
            throw new DomainException(DomainException.Code.CONFLICT, "刷新引用来源保存失败");
    }
    /** 缺少来源不能猜成系统任务；原Actor与执行ID必须一致。 */
    @Override public SegmentExecution segmentExecution(Actor actor) {
        if (actor.role() != Actor.Role.OPERATOR || actor.executionId() == null) throw denied();
        var json = routes.segmentExecution(actor);
        if (json == null) throw denied();
        var source = com.lrj.commerce.runtime.serialization.JsonCodec.read(json, SegmentExecution.class);
        if (!actor.tenantId().equals(source.route().tenantId()) || !source.expiresAt().isAfter(Instant.now())) throw denied();
        return source;
    }
    /** 原引用元数据来自认证边界；锁住独立路由后保存，不能把五秒准入窗当引用期限。 */
    @Override @Transactional
    public void rememberCouponDeliveryExecution(Actor actor, Capability capability, CouponDeliveryExecution source) {
        if (!couponDeliveryDurable(capability) || actor.role() != Actor.Role.OPERATOR || actor.executionId() == null || source == null
                || source.identity() == null || source.route() == null || !capability.family().equals(source.route().family())
                || !State.CENTRAL.name().equals(source.route().state()) || !actor.tenantId().equals(source.route().tenantId())
                || source.expiresAt() == null || !source.expiresAt().isAfter(Instant.now())
                || source.expiresAt().isAfter(Instant.now().plusSeconds(COUPON_DELIVERY_REFERENCE_SECONDS))
                || !Objects.equals(routes.lock(actor.tenantId(), capability.family()), source.route())) throw denied();
        if (routes.rememberCouponDeliveryExecution(actor, capability.code(), com.lrj.commerce.runtime.serialization.JsonCodec.write(source)) != 1)
            throw new DomainException(DomainException.Code.CONFLICT, "发券执行来源保存失败");
    }
    /** 进程恢复读取原执行记录，当前重新登录的引用不能用于旧方向。 */
    @Override public CouponDeliveryExecution couponDeliveryExecution(Actor actor, Capability capability) {
        if (!couponDeliveryDurable(capability) || actor.role() != Actor.Role.OPERATOR || actor.executionId() == null) throw denied();
        var json = routes.couponDeliveryExecution(actor, capability.code());
        if (json == null) throw denied();
        var source = com.lrj.commerce.runtime.serialization.JsonCodec.read(json, CouponDeliveryExecution.class);
        if (!actor.tenantId().equals(source.route().tenantId()) || !source.expiresAt().isAfter(Instant.now())) throw denied();
        return source;
    }
    /** 已批准的周期政策属于系统责任；撤销批准人Grant不改变已提交政策，但停止权威必须阻断。 */
    @Override public SegmentPolicyPermit segmentPolicy(String tenant, Route approvedRoute) {
        var route = routes.find(tenant, Capability.SEGMENT_REFRESH.family());
        if (approvedRoute == null) {
            if (route != null && (route.everCentral() || !java.util.Set.of(State.LEGACY.name(), State.SHADOW.name()).contains(route.state()))) throw denied();
        } else if (route == null || !State.CENTRAL.name().equals(route.state()) || !route.everCentral()
                || !State.CENTRAL.name().equals(approvedRoute.state()) || !Capability.SEGMENT_REFRESH.family().equals(approvedRoute.family())
                || !tenant.equals(approvedRoute.tenantId()) || !Objects.equals(route.authTenantId(), approvedRoute.authTenantId())
                || route.version() < approvedRoute.version()) throw denied();
        return new SegmentPolicyPermit(tenant, route, Instant.now().plusSeconds(5));
    }
    /** 政策真实来源由营销Owner校验，运行时只锁定持久权威与提交截止。 */
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void lock(SegmentPolicyPermit permit) {
        if (!Objects.equals(routes.lock(permit.tenant(), Capability.SEGMENT_REFRESH.family()), permit.route())
                || !permit.until().isAfter(Instant.now())) throw denied();
    }
    private static boolean memberCapability(Capability capability) {
        return java.util.Set.of(Capability.MEMBER_READ, Capability.MEMBER_CREATE, Capability.MEMBER_PROFILE_UPDATE, Capability.MEMBER_STATUS_UPDATE, Capability.GROWTH_READ, Capability.GROWTH_ADJUST, Capability.GROWTH_RECALCULATE, Capability.MEMBER_TAG_READ, Capability.MEMBER_TAG_DEFINE, Capability.MEMBER_TAG_ASSIGN, Capability.MEMBER_BEHAVIOR_READ, Capability.MEMBER_BEHAVIOR_UPDATE, Capability.MEMBER_BEHAVIOR_REBUILD, Capability.MEMBER_CYCLE_READ, Capability.MEMBER_CYCLE_EVALUATE, Capability.CYCLE_BENEFIT_GRANT, Capability.POINTS_READ, Capability.POINTS_ADJUST, Capability.POINTS_EXPIRE).contains(capability);
    }
    /** 集合许可的路由锁先于命令回执与业务写入。 */
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void lock(ScopePermit permit) {
        if (!Objects.equals(routes.lock(permit.tenant(), permit.capability().family()), permit.route())
                || !permit.until().isAfter(Instant.now())) throw denied();
    }
    /** 业务效果与主体归属同事务；标签定义的实际审计目标独立于会员域授权类型，不接受请求指定类型。 */
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void audit(Actor actor, ScopePermit permit, String operation, String key, String resourceId) {
        if (permit.identity() == null) return;
        com.lrj.commerce.kernel.Identifiers.require(resourceId);
        if (!actor.tenantId().equals(permit.tenant()) || routes.auditScoped(actor, permit, permit.capability().code(),
                auditType(permit.capability()), operation, key, resourceId,
                permit.capability() == Capability.STORE_CREATE ? resourceId : null) != 1)
            throw new DomainException(DomainException.Code.CONFLICT, "中央目录操作归属记录失败");
    }
    /** 创建使用可信提交结果，已有动作使用Owner版本；与业务、预算和回执共用事务。 */
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void auditVersion(Actor actor, ScopePermit permit, String operation, String key, String resourceId, long contentVersion) {
        if ((permit.capability() != Capability.CAMPAIGN_CREATE && !campaignAction(permit.capability()) && !newTenantObject(permit.capability())
                && !java.util.Set.of(Capability.SEGMENT_CREATE, Capability.SEGMENT_SCHEDULE, Capability.SEGMENT_REFRESH, Capability.SEGMENT_CONTROL, Capability.COUPON_DELIVERY_CREATE, Capability.COUPON_DELIVERY_CONTROL, Capability.OPS_PAGE_CREATE, Capability.JOURNEY_CREATE, Capability.JOURNEY_INSTANCE_CREATE, Capability.JOURNEY_INSTANCE_CONTROL, Capability.JOURNEY_SCAN_RETRY).contains(permit.capability())) || contentVersion <= 0 || !actor.tenantId().equals(permit.tenant())) throw denied();
        com.lrj.commerce.kernel.Identifiers.require(resourceId);
        if (permit.identity() == null) return;
        if (routes.auditVersioned(actor, permit, permit.capability().code(), operation, key, resourceId, contentVersion) != 1)
            throw new DomainException(DomainException.Code.CONFLICT, "中央活动版本操作归属记录失败");
    }
    /** 集合操作记录真实业务目标分类，不把字典或持久批次命令伪装成会员。 */
    private static String auditType(Capability capability) {
        return switch (capability) {
            case ORDER_EXPIRE -> "order_expiry_batch";
            case MEMBER_TAG_DEFINE -> "commerce_member_tag";
            case MEMBER_BEHAVIOR_REBUILD -> "commerce_member_behavior_batch";
            case CYCLE_BENEFIT_DEFINE -> "commerce_cycle_benefit";
            default -> capability.resourceType();
        };
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
