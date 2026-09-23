package com.lrj.commerce.runtime.persistence;
import org.apache.ibatis.annotations.*;

/** 可靠事件表由平台运行模块维护，业务效果必须与事件同事务。 */
@Mapper
public interface EventMapper {
    void insert(@Param("id") String id,@Param("tenant") String tenant,@Param("type") String type,@Param("aggregate") String aggregate,@Param("version") long version,@Param("json") String json);
    java.util.List<com.lrj.commerce.runtime.api.EventHandler.Event> pending(@Param("tenant") String tenant,@Param("types") java.util.Set<String> types,@Param("limit") int limit);
    com.lrj.commerce.runtime.api.EventHandler.Event lock(@Param("tenant") String tenant,@Param("id") String id);
    int inbox(@Param("consumer") String consumer,@Param("event") com.lrj.commerce.runtime.api.EventHandler.Event event);
    int delivered(@Param("id") String id);
    int failed(@Param("id") String id,@Param("delay") int delay);
    java.util.List<String> tenants(@Param("types") java.util.Set<String> types,@Param("after") String after);
    java.util.List<EventView> list(@Param("tenant") String tenant,@Param("after") String after,@Param("limit") int limit);
    int retry(@Param("tenant") String tenant,@Param("id") String id);
    record EventView(String eventId,String eventType,String aggregateId,String status,int attempts,java.time.Instant availableAt) { }
}
