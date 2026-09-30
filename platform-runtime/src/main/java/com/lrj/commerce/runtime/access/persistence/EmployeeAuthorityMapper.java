package com.lrj.commerce.runtime.access.persistence;

import com.lrj.commerce.runtime.api.access.EmployeeAccess.Route;
import org.apache.ibatis.annotations.*;

/** 只访问运行时拥有的分能力族权威表，不读写业务资源表。 */
@Mapper
public interface EmployeeAuthorityMapper {
    int audit(@Param("actor") com.lrj.commerce.runtime.api.identity.Actor actor,
              @Param("permit") com.lrj.commerce.runtime.api.access.EmployeeAccess.Permit permit,
              @Param("capability") String capability, @Param("operation") String operation, @Param("key") String key);
    Route find(@Param("tenant") String tenant, @Param("family") String family);
    Route lock(@Param("tenant") String tenant, @Param("family") String family);
    Route central(@Param("authTenant") String authTenant, @Param("family") String family);
}
