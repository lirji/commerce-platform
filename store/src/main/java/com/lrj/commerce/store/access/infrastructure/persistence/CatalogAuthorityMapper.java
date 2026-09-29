package com.lrj.commerce.store.access.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import com.lrj.commerce.store.access.api.CatalogAuthorityRoutes.Route;
import org.apache.ibatis.annotations.Param;

/** 权威状态属于商城资源Owner；直接调用用例同样读取该状态。 */
@Mapper
public interface CatalogAuthorityMapper {

    /** 不存在的迁移单元保持原路径；数据库不可用不能解释为不存在。 */
    Route find(@Param("tenant") String tenant);
    /** 仅用于选择HTTP认证边界，最终仍按可信本地Actor再校验。 */
    Route central(@Param("authTenant") String authTenant);
}
