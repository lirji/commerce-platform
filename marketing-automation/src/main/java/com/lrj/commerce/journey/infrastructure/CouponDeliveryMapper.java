package com.lrj.commerce.journey.infrastructure;
import com.lrj.commerce.journey.api.CouponDeliveryApi.*;
import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;
/** 批次检查点与回执原子提交，单收件人事务不长期锁整个人群。 */
@Mapper
public interface CouponDeliveryMapper {
    record Row(String contentJson,String status,String mode,String cursorMember,String revokeCursor,int processed,int issued,int skipped,int revoked,int kept,int attempts,String errorCode,Instant availableAt,long version) { }
    void insert(@Param("tenant") String tenant,@Param("input") Create input,@Param("json") String json,@Param("now") Instant now);
    Row find(@Param("tenant") String tenant,@Param("id") String id);
    Row lock(@Param("tenant") String tenant,@Param("id") String id);
    List<Row> list(@Param("tenant") String tenant,@Param("store") String store,@Param("after") String after,@Param("limit") int limit);
    String pending(@Param("tenant") String tenant,@Param("now") Instant now);
    List<String> tenants(@Param("after") String after,@Param("now") Instant now);
    int status(@Param("tenant") String tenant,@Param("id") String id,@Param("expected") long expected,@Param("status") String status,@Param("mode") String mode,@Param("now") Instant now);
    int advance(@Param("tenant") String tenant,@Param("id") String id,@Param("member") String member,@Param("issued") boolean issued);
    int advanceRevoke(@Param("tenant") String tenant,@Param("id") String id,@Param("member") String member,@Param("revoked") boolean revoked);
    void failed(@Param("tenant") String tenant,@Param("id") String id,@Param("code") String code,@Param("next") Instant next);
    Instant frequency(@Param("tenant") String tenant,@Param("member") String member);
    void frequencySet(@Param("tenant") String tenant,@Param("member") String member,@Param("next") Instant next);
    void recipient(@Param("tenant") String tenant,@Param("id") String id,@Param("input") Recipient input);
    List<Recipient> recipients(@Param("tenant") String tenant,@Param("id") String id,@Param("after") String after,@Param("limit") int limit);
    Recipient nextRevoke(@Param("tenant") String tenant,@Param("id") String id,@Param("after") String after);
    int revokeResult(@Param("tenant") String tenant,@Param("id") String id,@Param("member") String member,@Param("status") String status,@Param("code") String code);
}
