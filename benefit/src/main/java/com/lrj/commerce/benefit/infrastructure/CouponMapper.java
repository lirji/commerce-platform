package com.lrj.commerce.benefit.infrastructure;
import com.lrj.commerce.benefit.api.CouponApi.*;
import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;
/** 发券额度和钱包状态都由数据库条件更新决定。 */
@Mapper
public interface CouponMapper {
    record DefinitionRow(String definitionId,long version,String storeId,String name,String minimumSpend,String discountAmount,Instant validFrom,Instant validTo,int quota,boolean stackable,int issued,int platformFundingBps) { }
    void definition(@Param("tenant") String tenant,@Param("input") Definition input);
    DefinitionRow definitionFind(@Param("tenant") String tenant,@Param("id") String id,@Param("version") long version);
    DefinitionRow definitionLock(@Param("tenant") String tenant,@Param("id") String id,@Param("version") long version);
    List<DefinitionRow> definitions(@Param("tenant") String tenant,@Param("store") String store,@Param("after") String after,@Param("limit") int limit);
    int issue(@Param("tenant") String tenant,@Param("id") String id,@Param("version") long version);
    Coupon existing(@Param("tenant") String tenant,@Param("member") String member,@Param("id") String id,@Param("version") long version);
    void coupon(@Param("tenant") String tenant,@Param("member") String member,@Param("id") String id,@Param("definition") DefinitionRow definition);
    Coupon find(@Param("tenant") String tenant,@Param("member") String member,@Param("id") String id);
    record LockedCoupon(String couponId,String definitionId,long version,String memberId,String status,Instant validTo) { }
    LockedCoupon lock(@Param("tenant") String tenant,@Param("member") String member,@Param("id") String id);
    List<Coupon> wallet(@Param("tenant") String tenant,@Param("member") String member,@Param("after") String after,@Param("limit") int limit);
    int reserve(@Param("tenant") String tenant,@Param("id") String id,@Param("order") String order);
    void insertHold(@Param("tenant") String tenant,@Param("order") String order,@Param("id") String id,@Param("discount") String discount);
    record Hold(String couponId,String status) { }
    Hold hold(@Param("tenant") String tenant,@Param("order") String order);
    int finish(@Param("tenant") String tenant,@Param("order") String order,@Param("id") String id,@Param("expected") String expected,@Param("target") String target);
    int holdStatus(@Param("tenant") String tenant,@Param("order") String order,@Param("expected") String expected,@Param("target") String target);
}
