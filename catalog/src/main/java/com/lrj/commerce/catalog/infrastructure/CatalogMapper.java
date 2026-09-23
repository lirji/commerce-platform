package com.lrj.commerce.catalog.infrastructure;
import com.lrj.commerce.catalog.api.CatalogApi;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** 批量读取商品，避免报价逐行查询形成N+1。 */
@Mapper
public interface CatalogMapper {
    CatalogApi.Stats stats(String tenant,String store);
    List<CatalogApi.Price> priced(String tenant,String store,List<String> ids,String channel,java.time.Instant now);
    void snapshot(@Param("tenant") String tenant,@Param("id") String id,@Param("reason") String reason,@Param("actor") String actor);
    void insert(@Param("tenant") String tenant,@Param("input") CatalogApi.Create input);
    List<CatalogApi.View> list(@Param("tenant") String tenant,@Param("store") String store,@Param("after") String after,@Param("limit") int limit);
    List<CatalogApi.View> batch(@Param("tenant") String tenant,@Param("store") String store,@Param("ids") List<String> ids);
}
