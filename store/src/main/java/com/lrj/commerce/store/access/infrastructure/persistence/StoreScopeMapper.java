package com.lrj.commerce.store.access.infrastructure.persistence;
import com.lrj.commerce.runtime.api.scope.ScopeQuery.*;
import java.util.List;
import org.apache.ibatis.annotations.*;
/** 固定资源Owner SQL，不能把范围转换成调用方任意SQL。 */
@Mapper
public interface StoreScopeMapper {
    /** 分页前按完整路径过滤。 */
    List<Row> page(@Param("tenant") String tenant,@Param("scope") Filter scope,@Param("search") String search,@Param("after") String after,@Param("limit") int limit);
    /** 计数与列表共用SQL谓词。 */
    Stats stats(@Param("tenant") String tenant,@Param("scope") Filter scope,@Param("search") String search);
    /** 当前租户最小可信事实。 */
    Row fact(@Param("tenant") String tenant,@Param("id") String id);
    /** 有界批次按当前范围重新验证。 */
    List<Row> current(@Param("tenant") String tenant,@Param("scope") Filter scope,@Param("ids") List<String> ids);
}
