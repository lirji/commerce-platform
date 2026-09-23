package com.lrj.commerce.catalog.infrastructure;
import com.lrj.commerce.catalog.api.CatalogApi;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** 批量读取商品，避免报价逐行查询形成N+1。 */
@Mapper
public interface CatalogMapper {
    void insert(@Param("tenant") String tenant,@Param("input") CatalogApi.Create input);
    List<CatalogApi.View> list(@Param("tenant") String tenant,@Param("store") String store,@Param("after") String after,@Param("limit") int limit);
    List<CatalogApi.View> batch(@Param("tenant") String tenant,@Param("store") String store,@Param("ids") List<String> ids);
}
