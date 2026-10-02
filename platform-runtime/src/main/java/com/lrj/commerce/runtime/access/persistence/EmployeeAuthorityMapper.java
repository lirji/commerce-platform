package com.lrj.commerce.runtime.access.persistence;

import com.lrj.commerce.runtime.api.access.EmployeeAccess.Route;
import org.apache.ibatis.annotations.*;

/** 只访问运行时拥有的分能力族权威表，不读写业务资源表。 */
@Mapper
public interface EmployeeAuthorityMapper {
    int audit(@Param("actor") com.lrj.commerce.runtime.api.identity.Actor actor,
              @Param("permit") com.lrj.commerce.runtime.api.access.EmployeeAccess.Permit permit,
              @Param("capability") String capability, @Param("operation") String operation, @Param("key") String key);
    int auditScoped(@Param("actor") com.lrj.commerce.runtime.api.identity.Actor actor,
                    @Param("permit") com.lrj.commerce.runtime.api.access.EmployeeAccess.ScopePermit permit,
                    @Param("capability") String capability, @Param("resourceType") String resourceType,
                    @Param("operation") String operation, @Param("key") String key,
                    @Param("resourceId") String resourceId, @Param("storeId") String storeId);
    /** 内容版本单独落库，旧模式及既有资源的审计无需填充虚构版本。 */
    int auditVersioned(@Param("actor") com.lrj.commerce.runtime.api.identity.Actor actor,
                       @Param("permit") com.lrj.commerce.runtime.api.access.EmployeeAccess.ScopePermit permit,
                       @Param("capability") String capability, @Param("operation") String operation, @Param("key") String key,
                       @Param("resourceId") String resourceId, @Param("resourceVersion") long resourceVersion);
    /** 仅保存原刷新引用元数据，执行ID的唯一性由数据库承担。 */
    int rememberSegmentExecution(@Param("actor") com.lrj.commerce.runtime.api.identity.Actor actor, @Param("json") String json);
    String segmentExecution(@Param("actor") com.lrj.commerce.runtime.api.identity.Actor actor);
    /** 发券签发元数据与两个业务方向分离，执行引用只能对应一个能力。 */
    int rememberCouponDeliveryExecution(@Param("actor") com.lrj.commerce.runtime.api.identity.Actor actor,
                                       @Param("capability") String capability, @Param("json") String json);
    String couponDeliveryExecution(@Param("actor") com.lrj.commerce.runtime.api.identity.Actor actor, @Param("capability") String capability);
    Route find(@Param("tenant") String tenant, @Param("family") String family);
    Route lock(@Param("tenant") String tenant, @Param("family") String family);
    Route central(@Param("authTenant") String authTenant, @Param("family") String family);
}
