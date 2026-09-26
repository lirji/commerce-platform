package com.lrj.commerce.ordering.infrastructure;
import com.lrj.commerce.ordering.api.OrderApi.View;
import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;

/** 订单行只在所属域内部使用，跨域消费稳定API投影。 */
@Mapper
public interface OrderMapper {
    record Row(String orderId,String memberId,String storeId,String merchantId,String quoteId,String payable,String status,String paymentKind,long version,Instant createdAt,Instant expiresAt,String itemsJson,com.lrj.commerce.runtime.api.Actor.Channel channel) { }
    void insert(@Param("tenant") String tenant,@Param("view") View view,@Param("items") String items,@Param("address") byte[] address);
    Row read(@Param("tenant") String tenant,@Param("member") String member,@Param("id") String id);
    Row lock(@Param("tenant") String tenant,@Param("member") String member,@Param("id") String id);
    List<Row> list(@Param("tenant") String tenant,@Param("member") String member,@Param("after") String after,@Param("limit") int limit);
    int change(@Param("tenant") String tenant,@Param("id") String id,@Param("version") long version,@Param("status") String status);
    boolean hasPaidSince(String tenant,String member,String store,java.time.Instant since);
    Row internalRead(@Param("tenant") String tenant,@Param("id") String id);
    Row internalLock(@Param("tenant") String tenant,@Param("id") String id);
    /** 到期候选只排除退避中与已停止自动处理的订单；max与transientMax是两类失败的次数上限。 */
    record ExpiryCheck(String orderId,int expiryAttempts,int expiryTransientAttempts) { }
    List<Row> expired(@Param("tenant") String tenant,@Param("now") Instant now,@Param("max") int max,@Param("transientMax") int transientMax);
    List<String> expiryTenants(@Param("after") String after,@Param("now") Instant now,@Param("limit") int limit,@Param("max") int max,@Param("transientMax") int transientMax);
    List<ExpiryCheck> expiryDue(@Param("tenant") String tenant,@Param("now") Instant now,@Param("limit") int limit,@Param("max") int max,@Param("transientMax") int transientMax);
    Row expiredLock(@Param("tenant") String tenant,@Param("id") String id,@Param("now") Instant now,@Param("max") int max,@Param("transientMax") int transientMax);
    int expiryFailed(@Param("tenant") String tenant,@Param("id") String id,@Param("transient") boolean transientFailure,@Param("retryAt") Instant retryAt,@Param("error") String error);
    int expiryRetry(@Param("tenant") String tenant,@Param("id") String id,@Param("max") int max,@Param("transientMax") int transientMax);
    com.lrj.commerce.runtime.WorkLanes.Backlog expiryBacklog(@Param("now") Instant now,@Param("max") int max,@Param("transientMax") int transientMax);
}
