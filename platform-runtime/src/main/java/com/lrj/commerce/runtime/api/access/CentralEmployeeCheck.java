package com.lrj.commerce.runtime.api.access;

import com.lrj.commerce.runtime.api.identity.Actor;
import java.time.Instant;

/** 由app适配中央SDK；引用每次重新判定，不让本地OPERATOR自身产生业务权力。 */
public interface CentralEmployeeCheck {
    /** 中央范围已通过完整协议校验，仍由Owner绑定实际SQL字段。 */
    record ScopeDecision(EmployeeAccess.Identity identity, java.time.Instant until,
                         com.lrj.commerce.runtime.api.scope.ScopeQuery.Filter filter, String fingerprint) {}
    /** 集合无资源事实，复核引用的原Grant路径交集。 */
    ScopeDecision scope(Actor actor, EmployeeAccess.Capability capability, String authTenant);

    /** 对象与引用能力类型必须匹配；会员事实禁止附带虚构门店字段。 */
    Decision resource(Actor actor, EmployeeAccess.Capability capability, EmployeeAccess.ResourceFact fact, String authTenant);

    record Decision(EmployeeAccess.Identity identity, Instant until) {}
    /** 返回身份必须仍绑定同一本地Actor，错误区分拒绝与依赖故障。 */
    Decision require(Actor actor, EmployeeAccess.Capability capability, EmployeeAccess.StoreFact fact, String authTenant);
}
