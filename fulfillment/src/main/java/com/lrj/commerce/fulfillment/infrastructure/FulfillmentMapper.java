package com.lrj.commerce.fulfillment.infrastructure;
import com.lrj.commerce.fulfillment.api.FulfillmentApi.View;
import org.apache.ibatis.annotations.*;
import java.util.List;
/** 履约表只由本域写入；业务冲突通过条件更新暴露。 */
@Mapper
public interface FulfillmentMapper {
    void ensure(@Param("tenant") String tenant,@Param("order") String order);
    View find(@Param("tenant") String tenant,@Param("order") String order);
    View lock(@Param("tenant") String tenant,@Param("order") String order);
    List<View> list(@Param("tenant") String tenant,@Param("after") String after,@Param("limit") int limit);
    int ship(@Param("tenant") String tenant,@Param("order") String order,@Param("tracking") String tracking,@Param("provider") String provider,@Param("version") long version);
    int deliver(@Param("tenant") String tenant,@Param("order") String order,@Param("version") long version);
    int block(@Param("tenant") String tenant,@Param("order") String order,@Param("blocked") boolean blocked,@Param("status") String status,@Param("version") long version);
}
