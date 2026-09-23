package com.lrj.commerce.ordering.infrastructure;
import com.lrj.commerce.ordering.api.OrderApi.View;
import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;

/** 订单行只在所属域内部使用，跨域消费稳定API投影。 */
@Mapper
public interface OrderMapper {
    record Row(String orderId,String memberId,String storeId,String merchantId,String quoteId,String payable,String status,String paymentKind,long version,Instant createdAt,Instant expiresAt,String itemsJson) { }
    void insert(@Param("tenant") String tenant,@Param("view") View view,@Param("items") String items,@Param("address") byte[] address);
    Row read(@Param("tenant") String tenant,@Param("member") String member,@Param("id") String id);
    Row lock(@Param("tenant") String tenant,@Param("member") String member,@Param("id") String id);
    List<Row> list(@Param("tenant") String tenant,@Param("member") String member,@Param("after") String after,@Param("limit") int limit);
    int change(@Param("tenant") String tenant,@Param("id") String id,@Param("version") long version,@Param("status") String status);
    boolean hasPaidSince(String tenant,String member,String store,java.time.Instant since);
    Row internalRead(@Param("tenant") String tenant,@Param("id") String id);
    Row internalLock(@Param("tenant") String tenant,@Param("id") String id);
    List<Row> expired(@Param("tenant") String tenant,@Param("now") Instant now);
}
