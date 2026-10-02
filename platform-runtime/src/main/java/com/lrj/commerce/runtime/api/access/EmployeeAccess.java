package com.lrj.commerce.runtime.api.access;

import com.lrj.commerce.runtime.api.identity.Actor;
import java.time.Instant;

/** 员工用例的实时能力门禁；领域只传Owner事实，不接触中央SDK或用户Token。 */
public interface EmployeeAccess {
    /** 原最大刷新TTL加签发请求余量，只限定引用，不扩大原Grant或五秒提交窗。 */
    long SEGMENT_REFRESH_REFERENCE_SECONDS = 86460;
    /** 发放与首次撤回单次来源上限；实际发放另受业务deadline约束。 */
    long COUPON_DELIVERY_REFERENCE_SECONDS = 604860;
    /** 只登记已实现的用例，不能按请求字符串拼能力或迁移单元。 */
    enum Capability {
        INVENTORY_READ("commerce.inventory.read", "INVENTORY", "store"), INVENTORY_RECEIVE("commerce.inventory.receive", "INVENTORY", "store"),
        MERCHANT_READ("commerce.merchant.read", "DIRECTORY", "merchant"), MERCHANT_CREATE("commerce.merchant.create", "DIRECTORY", "merchant"),
        STORE_DIRECTORY_READ("commerce.store.directory.read", "DIRECTORY", "store"), STORE_CREATE("commerce.store.create", "DIRECTORY", "store"),
        MEMBER_READ("commerce.member.read", "MEMBER_PROFILE", "commerce_member"), MEMBER_CREATE("commerce.member.create", "MEMBER_PROFILE", "commerce_member"),
        MEMBER_PROFILE_UPDATE("commerce.member.profile.update", "MEMBER_PROFILE", "commerce_member"), MEMBER_STATUS_UPDATE("commerce.member.status.update", "MEMBER_PROFILE", "commerce_member"),
        GROWTH_READ("commerce.growth.read", "MEMBER_GROWTH", "commerce_member"), GROWTH_ADJUST("commerce.growth.adjust", "MEMBER_GROWTH", "commerce_member"),
        GROWTH_RECALCULATE("commerce.growth.recalculate", "MEMBER_GROWTH", "commerce_member"),
        GROWTH_POLICY_READ("commerce.growth.policy.read", "MEMBER_GROWTH", "commerce_member_policy"), GROWTH_POLICY_PUBLISH("commerce.growth.policy.publish", "MEMBER_GROWTH", "commerce_member_policy"),
        MEMBER_TAG_READ("commerce.member_tag.read", "MEMBER_TAG", "commerce_member"), MEMBER_TAG_DEFINE("commerce.member_tag.define", "MEMBER_TAG", "commerce_member"),
        MEMBER_TAG_ASSIGN("commerce.member_tag.assign", "MEMBER_TAG", "commerce_member"),
        MEMBER_BEHAVIOR_READ("commerce.member_behavior.read", "MEMBER_BEHAVIOR", "commerce_member"),
        MEMBER_BEHAVIOR_UPDATE("commerce.member_behavior.update", "MEMBER_BEHAVIOR", "commerce_member"),
        MEMBER_BEHAVIOR_REBUILD("commerce.member_behavior.rebuild", "MEMBER_BEHAVIOR", "commerce_member"),
        MEMBER_CYCLE_POLICY_READ("commerce.member_cycle.policy.read", "MEMBER_CYCLE", "commerce_member_policy"),
        MEMBER_CYCLE_POLICY_PUBLISH("commerce.member_cycle.policy.publish", "MEMBER_CYCLE", "commerce_member_policy"),
        MEMBER_CYCLE_READ("commerce.member_cycle.read", "MEMBER_CYCLE", "commerce_member"),
        MEMBER_CYCLE_EVALUATE("commerce.member_cycle.evaluate", "MEMBER_CYCLE", "commerce_member"),
        CYCLE_BENEFIT_READ("commerce.cycle_benefit.read", "CYCLE_BENEFIT", "commerce_member_policy"),
        CYCLE_BENEFIT_DEFINE("commerce.cycle_benefit.define", "CYCLE_BENEFIT", "commerce_member_policy"),
        CYCLE_BENEFIT_GRANT("commerce.cycle_benefit.grant", "CYCLE_BENEFIT", "commerce_member"),
        POINTS_POLICY_READ("commerce.points.policy.read", "MEMBER_POINTS", "commerce_member_policy"),
        POINTS_POLICY_PUBLISH("commerce.points.policy.publish", "MEMBER_POINTS", "commerce_member_policy"),
        POINTS_READ("commerce.points.read", "MEMBER_POINTS", "commerce_member"),
        POINTS_ADJUST("commerce.points.adjust", "MEMBER_POINTS", "commerce_member"),
        POINTS_EXPIRE("commerce.points.expire", "MEMBER_POINTS", "commerce_member"),
        POINT_OFFER_READ("commerce.point_offer.read", "POINT_OFFER", "point_offer"),
        POINT_OFFER_DEFINE("commerce.point_offer.define", "POINT_OFFER", "point_offer"),
        POINT_OFFER_STATUS_UPDATE("commerce.point_offer.status.update", "POINT_OFFER", "point_offer"),
        COUPON_DEFINITION_READ("commerce.coupon_definition.read", "COUPON_DEFINITION", "coupon_definition"),
        COUPON_DEFINITION_CREATE("commerce.coupon_definition.create", "COUPON_DEFINITION", "coupon_definition"),
        ENTITLEMENT_DEFINITION_READ("commerce.entitlement_definition.read", "ENTITLEMENT_DEFINITION", "entitlement_definition"),
        ENTITLEMENT_DEFINITION_CREATE("commerce.entitlement_definition.create", "ENTITLEMENT_DEFINITION", "entitlement_definition"),
        ENTITLEMENT_READ("commerce.entitlement.read", "ENTITLEMENT", "entitlement"),
        ENTITLEMENT_RESOLVE("commerce.entitlement.resolve", "ENTITLEMENT", "entitlement"),
        RULE_READ("commerce.rule.read", "RULE", "marketing_rule"),
        RULE_CREATE("commerce.rule.create", "RULE", "marketing_rule"),
        RULE_PUBLISH("commerce.rule.publish", "RULE", "marketing_rule"),
        AUDIENCE_READ("commerce.audience.read", "AUDIENCE", "audience"),
        AUDIENCE_CREATE("commerce.audience.create", "AUDIENCE", "audience"),
        CAMPAIGN_READ("commerce.campaign.read", "CAMPAIGN", "campaign"),
        CAMPAIGN_CREATE("commerce.campaign.create", "CAMPAIGN", "campaign"),
        CAMPAIGN_PREVIEW("commerce.campaign.preview", "CAMPAIGN", "campaign"),
        CAMPAIGN_SUBMIT("commerce.campaign.submit", "CAMPAIGN", "campaign"),
        CAMPAIGN_APPROVE("commerce.campaign.approve", "CAMPAIGN", "campaign"),
        CAMPAIGN_REJECT("commerce.campaign.reject", "CAMPAIGN", "campaign"),
        CAMPAIGN_PUBLISH("commerce.campaign.publish", "CAMPAIGN", "campaign"),
        CAMPAIGN_PAUSE("commerce.campaign.pause", "CAMPAIGN", "campaign"),
        BUDGET_READ("commerce.budget.read", "CAMPAIGN", "campaign"),
        SEGMENT_READ("commerce.segment.read", "SEGMENT", "segment"),
        SEGMENT_CREATE("commerce.segment.create", "SEGMENT", "segment"),
        SEGMENT_SCHEDULE("commerce.segment.schedule", "SEGMENT", "segment"),
        SEGMENT_REFRESH("commerce.segment.refresh", "SEGMENT", "segment"),
        SEGMENT_CONTROL("commerce.segment.control", "SEGMENT", "segment"),
        SEGMENT_PUMP("commerce.segment.pump", "SEGMENT", "segment"),
        COUPON_DELIVERY_CREATE("commerce.coupon_delivery.create", "COUPON_DELIVERY", "coupon_delivery"),
        COUPON_DELIVERY_READ("commerce.coupon_delivery.read", "COUPON_DELIVERY", "coupon_delivery"),
        COUPON_DELIVERY_CONTROL("commerce.coupon_delivery.control", "COUPON_DELIVERY", "coupon_delivery"),
        COUPON_DELIVERY_PUMP("commerce.coupon_delivery.pump", "COUPON_DELIVERY", "coupon_delivery"),
        JOURNEY_CREATE("commerce.journey.create", "JOURNEY", "journey"),
        JOURNEY_VALIDATE("commerce.journey.validate", "JOURNEY", "journey"),
        JOURNEY_PREVIEW("commerce.journey.preview", "JOURNEY", "journey"),
        JOURNEY_READ("commerce.journey.read", "JOURNEY", "journey"),
        JOURNEY_SUBMIT("commerce.journey.submit", "JOURNEY", "journey"),
        JOURNEY_APPROVE("commerce.journey.approve", "JOURNEY", "journey"),
        JOURNEY_REJECT("commerce.journey.reject", "JOURNEY", "journey"),
        JOURNEY_PUBLISH("commerce.journey.publish", "JOURNEY", "journey"),
        JOURNEY_PAUSE("commerce.journey.pause", "JOURNEY", "journey"),
        JOURNEY_PUMP("commerce.journey.pump", "JOURNEY", "journey"),
        JOURNEY_INSTANCE_CREATE("commerce.journey_instance.create", "JOURNEY", "journey_instance", 2592060),
        JOURNEY_INSTANCE_READ("commerce.journey_instance.read", "JOURNEY", "journey_instance"),
        JOURNEY_INSTANCE_CONTROL("commerce.journey_instance.control", "JOURNEY", "journey_instance"),
        JOURNEY_SCAN_READ("commerce.journey_scan.read", "JOURNEY", "journey_scan"),
        JOURNEY_SCAN_RETRY("commerce.journey_scan.retry", "JOURNEY", "journey_scan"),
        MARKETING_EFFECT_READ("commerce.marketing_effect.read", "MARKETING_REPORT", "marketing_report"),
        MARKETING_EFFECT_REBUILD("commerce.marketing_effect.rebuild", "MARKETING_REPORT", "marketing_report"),
        MARKETING_EXECUTION_READ("commerce.marketing_execution.read", "MARKETING_REPORT", "marketing_report"),
        ORDER_READ("commerce.order.read", "ORDER", "store"),
        ORDER_EXPIRE("commerce.order.expire", "ORDER", "store"),
        ORDER_EXPIRY_RETRY("commerce.order.expiry.retry", "ORDER", "store"),
        PAYMENT_READ("commerce.payment.read", "PAYMENT", "store"),
        PAYMENT_RECONCILE("commerce.payment.reconcile", "PAYMENT", "store"),
        FULFILLMENT_READ("commerce.fulfillment.read", "FULFILLMENT", "store"),
        FULFILLMENT_SHIP("commerce.fulfillment.ship", "FULFILLMENT", "store"),
        FULFILLMENT_DELIVER("commerce.fulfillment.deliver", "FULFILLMENT", "store"),
        AFTERSALE_READ("commerce.aftersale.read", "AFTERSALE", "store"),
        AFTERSALE_APPROVE("commerce.aftersale.approve", "AFTERSALE", "store"),
        AFTERSALE_REJECT("commerce.aftersale.reject", "AFTERSALE", "store"),
        AFTERSALE_RECEIVE_RETURN("commerce.aftersale.receive_return", "AFTERSALE", "store"),
        REFUND_READ("commerce.refund.read", "REFUND", "store"),
        REFUND_RECONCILE("commerce.refund.reconcile", "REFUND", "store"),
        OPS_PAGE_READ("commerce.ops_page.read", "OPS_PAGE", "ops_page"),
        OPS_PAGE_CREATE("commerce.ops_page.create", "OPS_PAGE", "ops_page"),
        OPS_PAGE_PREVIEW("commerce.ops_page.preview", "OPS_PAGE", "ops_page"),
        OPS_PAGE_SUBMIT("commerce.ops_page.submit", "OPS_PAGE", "ops_page"),
        OPS_PAGE_APPROVE("commerce.ops_page.approve", "OPS_PAGE", "ops_page"),
        OPS_PAGE_REJECT("commerce.ops_page.reject", "OPS_PAGE", "ops_page"),
        OPS_PAGE_PUBLISH("commerce.ops_page.publish", "OPS_PAGE", "ops_page"),
        OPS_PAGE_PAUSE("commerce.ops_page.pause", "OPS_PAGE", "ops_page"),
        OPS_PAGE_ROLLBACK("commerce.ops_page.rollback", "OPS_PAGE", "ops_page"),
        OPS_PAGE_EXECUTE("commerce.ops_page.execute", "OPS_PAGE", "ops_page"),
        EVENT_READ("commerce.event.read", "EVENT", "commerce_runtime"),
        EVENT_RETRY("commerce.event.retry", "EVENT", "commerce_runtime"),
        EVENT_PUMP("commerce.event.pump", "EVENT", "commerce_runtime"),
        RUNTIME_READ("commerce.runtime.read", "RUNTIME", "commerce_runtime"),
        RUNTIME_RECOVER("commerce.runtime.recover", "RUNTIME", "commerce_runtime"),
        RUNTIME_REPLAY_PREVIEW("commerce.runtime.replay.preview", "RUNTIME", "commerce_runtime"),
        RUNTIME_REPLAY_CREATE("commerce.runtime.replay.create", "RUNTIME", "commerce_runtime", 86460),
        RUNTIME_REPLAY_CONTROL("commerce.runtime.replay.control", "RUNTIME", "commerce_runtime");
        private final String code, family, resourceType;
        private final long referenceSeconds;
        Capability(String code, String family, String resourceType) { this(code, family, resourceType, 60); }
        Capability(String code, String family, String resourceType, long referenceSeconds) { this.code=code; this.family=family; this.resourceType=resourceType; this.referenceSeconds=referenceSeconds; }
        /** 精确能力决定引用上限；同步资格永远不能继承长期来源。 */
        public long referenceSeconds() { return referenceSeconds; }
        public String code() { return code; }
        public String family() { return family; }
        public String resourceType() { return resourceType; }
    }
    /** 首片资源固定为真实门店，不能用SKU编号替代门店Facts。 */
    record StoreFact(String id, long version) {}
    /** 对象事实由对应业务Owner提供，类型不可由HTTP输入猜测。 */
    record ResourceFact(String type, String id, long version) {}
    /** 组合许可保留实际目标版本，集合资格本身不能冒充已核对对象。 */
    record ResourcePermit(ScopePermit scope, ResourceFact fact) {}
    /** 在已核对集合资格上追加可信对象判权，领域不需要中央协议类型。 */
    ResourcePermit resource(Actor actor, ScopePermit scope, ResourceFact fact);
    /** 无Token的单次许可；中央身份摘要稳定，执行引用nonce不进入幂等摘要。 */
    record Identity(String principalId, String membershipId, long generation) {}
    record Permit(Capability capability, String tenant, StoreFact fact, Route route, Identity identity, Instant until) {}
    /** 集合许可没有对象事实；过滤器仅交给拥有业务表的Owner。 */
    record ScopePermit(Capability capability, String tenant, com.lrj.commerce.runtime.api.scope.ScopeQuery.Filter filter,
                       Route route, Identity identity, String fingerprint, Instant until) {}
    /** 集合/创建每次重新复核引用，不缓存ALLOW。 */
    ScopePermit scope(Actor actor, Capability capability);
    /** 创建回执同样必须通过当前路由和准入截止检查。 */
    void lock(ScopePermit permit);
    /** 记录本事务实际创建目标，不拿待创建目标作为授权事实。 */
    void audit(Actor actor, ScopePermit permit, String operation, String commandKey, String resourceId);
    /** 活动记录实际不可变内容版本，不能把状态锁版本当成授权或审计版本。 */
    void auditVersion(Actor actor, ScopePermit permit, String operation, String commandKey, String resourceId, long contentVersion);
    /** 读取后权限或身份变化时拒绝返回旧结果。 */
    static void requireSame(ScopePermit before, ScopePermit after) {
        if(!java.util.Objects.equals(before.route(), after.route()) || !java.util.Objects.equals(before.identity(), after.identity())
                || !java.util.Objects.equals(before.fingerprint(), after.fingerprint()))
            throw new com.lrj.commerce.kernel.DomainException(com.lrj.commerce.kernel.DomainException.Code.FORBIDDEN, "目录授权上下文已变化");
    }
    /** 原手工引用的准确截止与分区；准入许可的五秒窗口不能替代任务引用期限。 */
    record SegmentExecution(Identity identity, Route route, String applicationId, String environment,
                            String callerServiceId, long membershipVersion, long principalVersion, Instant expiresAt) {}
    /** 只在中央签发segment.refresh后保存无Token元数据，随后由Owner复制进任务。 */
    void rememberSegmentExecution(Actor actor, SegmentExecution source);
    /** 进程恢复依赖持久元数据，不能用新的员工引用替代原来源。 */
    SegmentExecution segmentExecution(Actor actor);
    /** 发券两个方向保存同结构元数据，能力必须在准确调用处单独限定。 */
    record CouponDeliveryExecution(Identity identity, Route route, String applicationId, String environment,
                                   String callerServiceId, long membershipVersion, long principalVersion, Instant expiresAt) {}
    /** 仅实际持久POST登记，GET资格不生成长期任务来源。 */
    void rememberCouponDeliveryExecution(Actor actor, Capability capability, CouponDeliveryExecution source);
    /** 通过原Actor、执行引用及准确能力读取，不以新控制来源替换旧方向。 */
    CouponDeliveryExecution couponDeliveryExecution(Actor actor, Capability capability);
    /** 独立系统政策只核验SEGMENT权威，不伪造员工或延续批准人的Grant。 */
    record SegmentPolicyPermit(String tenant, Route route, Instant until) {}
    SegmentPolicyPermit segmentPolicy(String tenant, Route approvedRoute);
    /** 系统政策提交也与停止/切换串行，Owner另行核验真实政策和固定定义。 */
    void lock(SegmentPolicyPermit permit);
    /** 有限任务原来源无Token；能力单独绑定，持久记录不可由后来控制/推进覆盖。 */
    record FiniteExecution(Identity identity, Route route, String applicationId, String environment,
                           String callerServiceId, long membershipVersion, long principalVersion, Instant expiresAt) {}
    void rememberExecution(Actor actor, Capability capability, FiniteExecution source);
    FiniteExecution execution(Actor actor, Capability capability);
    /** Owner证明真实已批准固定政策，运行时只提供同分区权威与有界提交栅栏。 */
    record PolicyPermit(String tenant, Capability capability, Route route, Instant until) {}
    PolicyPermit policy(String tenant, Capability capability, Route approvedRoute);
    void lock(PolicyPermit permit);
    /** 状态持久化，不能由HTTP头或运行开关推断当前权威。 */
    record Route(String tenantId, String authTenantId, String family, String state, boolean everCentral, long version) {}
    /** 每次业务访问都要调用，包括非HTTP入口和重试。 */
    Permit require(Actor actor, Capability capability, StoreFact fact);
    /** 在命令事务中锁定路由，读取旧回执前也必须执行。 */
    void lock(Permit permit);
    /** 成功命令与中央主体代际同事务留证，旧映射变更不抹掉操作归属。 */
    void audit(Actor actor, Permit permit, String operation, String commandKey);
    /** 认证边界只查询已经接管的单元。 */
    Route central(String authTenant, Capability capability);
}
