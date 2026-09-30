package com.lrj.commerce.runtime.api.access;

import com.lrj.commerce.runtime.api.identity.Actor;
import java.time.Instant;

/** 由app适配中央SDK；引用每次重新判定，不让本地OPERATOR自身产生业务权力。 */
public interface CentralEmployeeCheck {
    record Decision(EmployeeAccess.Identity identity, Instant until) {}
    /** 返回身份必须仍绑定同一本地Actor，错误区分拒绝与依赖故障。 */
    Decision require(Actor actor, EmployeeAccess.Capability capability, EmployeeAccess.StoreFact fact, String authTenant);
}
