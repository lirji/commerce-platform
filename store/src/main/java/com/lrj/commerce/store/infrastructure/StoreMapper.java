package com.lrj.commerce.store.infrastructure;
import com.lrj.commerce.store.api.StoreApi;
import org.apache.ibatis.annotations.*;
import java.util.List;
/** 仅访问store权威表，所有谓词绑定可信tenant。 */
@Mapper
public interface StoreMapper {
 void insert(@Param("tenant") String tenant,@Param("input") StoreApi.Create input);
 StoreApi.View find(@Param("tenant") String tenant,@Param("id") String id);
 List<StoreApi.View> list(@Param("tenant") String tenant,@Param("after") String after,@Param("limit") int limit);
 
}
