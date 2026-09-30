package com.lrj.commerce.runtime.api.access;

import com.lrj.commerce.runtime.api.identity.Actor;
import java.time.Instant;

/** 员工用例的实时能力门禁；领域只传Owner事实，不接触中央SDK或用户Token。 */
public interface EmployeeAccess {
    /** 只登记已实现的用例，不能按请求字符串拼能力或迁移单元。 */
    enum Capability {
        INVENTORY_READ("commerce.inventory.read", "INVENTORY", "store"), INVENTORY_RECEIVE("commerce.inventory.receive", "INVENTORY", "store"),
        MERCHANT_READ("commerce.merchant.read", "DIRECTORY", "merchant"), MERCHANT_CREATE("commerce.merchant.create", "DIRECTORY", "merchant"),
        STORE_DIRECTORY_READ("commerce.store.directory.read", "DIRECTORY", "store"), STORE_CREATE("commerce.store.create", "DIRECTORY", "store"),
        MEMBER_READ("commerce.member.read", "MEMBER_PROFILE", "commerce_member"), MEMBER_CREATE("commerce.member.create", "MEMBER_PROFILE", "commerce_member"),
        MEMBER_PROFILE_UPDATE("commerce.member.profile.update", "MEMBER_PROFILE", "commerce_member"), MEMBER_STATUS_UPDATE("commerce.member.status.update", "MEMBER_PROFILE", "commerce_member"),
        GROWTH_READ("commerce.growth.read", "MEMBER_GROWTH", "commerce_member"), GROWTH_ADJUST("commerce.growth.adjust", "MEMBER_GROWTH", "commerce_member"),
        GROWTH_RECALCULATE("commerce.growth.recalculate", "MEMBER_GROWTH", "commerce_member"),
        GROWTH_POLICY_READ("commerce.growth.policy.read", "MEMBER_GROWTH", "commerce_member_policy"), GROWTH_POLICY_PUBLISH("commerce.growth.policy.publish", "MEMBER_GROWTH", "commerce_member_policy");
        private final String code, family, resourceType;
        Capability(String code, String family, String resourceType) { this.code = code; this.family = family; this.resourceType = resourceType; }
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
    /** 读取后权限或身份变化时拒绝返回旧结果。 */
    static void requireSame(ScopePermit before, ScopePermit after) {
        if(!java.util.Objects.equals(before.route(), after.route()) || !java.util.Objects.equals(before.identity(), after.identity())
                || !java.util.Objects.equals(before.fingerprint(), after.fingerprint()))
            throw new com.lrj.commerce.kernel.DomainException(com.lrj.commerce.kernel.DomainException.Code.FORBIDDEN, "目录授权上下文已变化");
    }
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
