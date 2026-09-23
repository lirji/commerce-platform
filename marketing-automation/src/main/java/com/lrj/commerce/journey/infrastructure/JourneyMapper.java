package com.lrj.commerce.journey.infrastructure;
import com.lrj.commerce.journey.api.JourneyApi.*;
import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;
/** 旅程定义、检查点和站内信由本模块独占写入。 */
@Mapper
public interface JourneyMapper {
    record Cap(long entryWindow,int entries,long notificationWindow,int notifications) { }
    void ensureCap(String tenant,String journey,String member);
    Cap lockCap(String tenant,String journey,String member);
    void cap(String tenant,String journey,String member,boolean notification,long window,int count,boolean suppressed);
    boolean effectExists(String tenant,String id);
    void effect(String tenant,String id,Definition definition,String member,String kind);
    List<Row> triggered(String tenant,String trigger,String store,Instant now,Instant occurred);
    List<EffectSummary> effects(String tenant,String store,Instant from,Instant to,String after,int limit);
    record Row(String journeyId,long version,String storeId,String status,long lockVersion,String definitionJson) { }
    void definition(String tenant,Definition input,String json);
    Row find(String tenant,String id,long version);
    List<Row> group(String tenant,String id);
    List<Row> definitions(String tenant,String after,int limit);
    List<Row> published(String tenant,String store,Instant now);
    int pauseOthers(String tenant,String id);
    int change(String tenant,String id,long version,long expected,String status);
    void instance(String tenant,String id,Definition definition,String member,String order,String event,Instant now,Instant deadline);
    Instance byEvent(String tenant,String journey,long version,String event);
    Instance findInstance(String tenant,String id);
    Instance lock(String tenant,String id);
    Instance dueLock(String tenant,String id,Instant now);
    List<Instance> instances(String tenant,String member,String after,int limit);
    List<Instance> orderInstances(String tenant,String order);
    List<String> tenants(String after,Instant now);
    List<Instance> due(String tenant,Instant now);
    int advance(String tenant,Instance previous,String node,String status,Instant due,String result);
    int failed(String tenant,String id,long version,Instant due);
    int control(String tenant,String id,long version,String status,Instant now);
    void notify(String tenant,String id,String instance,String node,String member,String title,String body);
    List<Notification> notifications(String tenant,String member,String after,int limit);
}
