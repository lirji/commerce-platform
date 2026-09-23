package com.lrj.commerce.payment.infrastructure;
import com.lrj.commerce.payment.api.*;
import org.apache.ibatis.annotations.*;
/** 支付尝试与沙箱渠道账本各自持久化，查询结果是唯一可信状态来源。 */
@Mapper
public interface PaymentMapper {
    PaymentApi.View byOrder(@Param("tenant") String tenant,@Param("order") String order);
    PaymentApi.View find(@Param("tenant") String tenant,@Param("id") String id);
    PaymentApi.View lock(@Param("tenant") String tenant,@Param("id") String id);
    void insert(@Param("tenant") String tenant,@Param("view") PaymentApi.View view);
    int change(@Param("tenant") String tenant,@Param("id") String id,@Param("version") long version,@Param("status") String status,@Param("transaction") String transaction,@Param("evidence") String evidence);
    void ensureChannel(@Param("tenant") String tenant,@Param("view") PaymentApi.View view);
    PaymentChannel.Evidence channel(@Param("tenant") String tenant,@Param("id") String id);
    int channelFact(@Param("tenant") String tenant,@Param("id") String id,@Param("status") String status,@Param("transaction") String transaction);
    int channelClose(@Param("tenant") String tenant,@Param("id") String id);
    record Check(String tenantId,String paymentId,String orderId,int checkAttempts) { }
    java.util.List<Check> due(@Param("tenant") String tenant);
    java.util.List<String> dueTenants(@Param("after") String after);
    int claimCheck(@Param("tenant") String tenant,@Param("id") String id,@Param("attempts") int attempts,@Param("delay") int delay);
    int reserveRefund(@Param("tenant") String tenant,@Param("id") String id,@Param("amount") String amount);
}
