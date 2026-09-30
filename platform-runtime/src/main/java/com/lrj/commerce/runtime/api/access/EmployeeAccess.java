package com.lrj.commerce.runtime.api.access;

import com.lrj.commerce.runtime.api.identity.Actor;
import java.time.Instant;

/** 员工用例的实时能力门禁；领域只传Owner事实，不接触中央SDK或用户Token。 */
public interface EmployeeAccess {
    /** 只登记已实现的用例，不能按请求字符串拼能力或迁移单元。 */
    enum Capability {
        INVENTORY_READ("commerce.inventory.read"), INVENTORY_RECEIVE("commerce.inventory.receive");
        private final String code;
        Capability(String code) { this.code = code; }
        public String code() { return code; }
        public String family() { return "INVENTORY"; }
    }
    /** 首片资源固定为真实门店，不能用SKU编号替代门店Facts。 */
    record StoreFact(String id, long version) {}
    /** 无Token的单次许可；中央身份摘要稳定，执行引用nonce不进入幂等摘要。 */
    record Identity(String principalId, String membershipId, long generation) {}
    record Permit(Capability capability, String tenant, StoreFact fact, Route route, Identity identity, Instant until) {}
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
