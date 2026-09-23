package com.lrj.commerce.store.infrastructure;
import com.lrj.commerce.store.api.*;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** 授权及范围过滤均在SQL绑定可信主体，避免先查全店再在页面过滤。 */
@Mapper
public interface StoreAccessMapper {
 void insert(@Param("tenant") String tenant,@Param("input") StoreAccessApi.Create input);
 StoreAccessApi.Grant find(@Param("tenant") String tenant,@Param("id") String id);
 int change(@Param("tenant") String tenant,@Param("id") String id,@Param("input") StoreAccessApi.Change input);
 List<StoreAccessApi.Grant> list(@Param("tenant") String tenant,@Param("after") String after,@Param("limit") int limit);
 boolean allowed(@Param("tenant") String tenant,@Param("actor") String actor,@Param("store") String store,@Param("merchant") String merchant);
 List<StoreApi.View> stores(@Param("tenant") String tenant,@Param("actor") String actor,@Param("after") String after,@Param("limit") int limit);
}
