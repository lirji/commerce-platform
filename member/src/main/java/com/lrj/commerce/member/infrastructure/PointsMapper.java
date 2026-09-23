package com.lrj.commerce.member.infrastructure;
import com.lrj.commerce.member.api.*;
import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;

/** 积分策略、贡献和批次由会员域独占；所有写入由会员行锁保护。 */
@Mapper
public interface PointsMapper {
    record PolicyRow(String policyJson) { }
    record Account(long debt,long version) { }
    record Totals(long credit,long held) { }
    record Source(String memberId,String paid,boolean completed,long policyVersion,String earnRate,int expiryDays,long contribution) { }
    record Refund(String orderId,String amount) { }
    record Lot(String lotId,String memberId,long policyVersion,long credited,long remaining,long held,long expired,Instant expiresAt) { }
    record Due(String tenantId,String memberId,String lotId) { }
    void policy(@Param("tenant") String tenant,@Param("p") MemberPointsApi.Policy p,@Param("json") String json);
    PolicyRow effective(@Param("tenant") String tenant,@Param("at") Instant at);
    List<PolicyRow> policies(@Param("tenant") String tenant,@Param("after") long after,@Param("limit") int limit);
    void ensure(@Param("tenant") String tenant,@Param("member") String member);
    Account account(@Param("tenant") String tenant,@Param("member") String member);
    int accountChange(@Param("tenant") String tenant,@Param("member") String member,@Param("debt") long debt,@Param("expected") long expected);
    Totals totals(@Param("tenant") String tenant,@Param("member") String member,@Param("at") Instant at);
    Source source(@Param("tenant") String tenant,@Param("order") String order);
    void sourceInsert(@Param("tenant") String tenant,@Param("f") MemberGrowthApi.OrderFact fact,@Param("version") long version,@Param("rate") String rate,@Param("days") int days);
    int sourceChange(@Param("tenant") String tenant,@Param("order") String order,@Param("completed") boolean completed,@Param("contribution") long contribution);
    Refund refund(@Param("tenant") String tenant,@Param("id") String id);
    void refundInsert(@Param("tenant") String tenant,@Param("f") MemberGrowthApi.OrderFact fact);
    String refundTotal(@Param("tenant") String tenant,@Param("order") String order);
    Lot lot(@Param("tenant") String tenant,@Param("id") String id);
    void insertLot(@Param("tenant") String tenant,@Param("lot") Lot lot);
    int changeLot(@Param("tenant") String tenant,@Param("id") String id,@Param("remaining") long remaining,@Param("held") long held,@Param("expired") long expired);
    List<Lot> availableLots(@Param("tenant") String tenant,@Param("member") String member,@Param("at") Instant at,@Param("limit") int limit);
    List<Lot> expiredLots(@Param("tenant") String tenant,@Param("member") String member,@Param("at") Instant at,@Param("limit") int limit);
    List<Due> due(@Param("at") Instant at,@Param("limit") int limit);
    void entry(@Param("tenant") String tenant,@Param("member") String member,@Param("action") String action,@Param("source") String source,@Param("delta") long delta,@Param("wallet") MemberPointsApi.Wallet wallet,@Param("policy") long policy,@Param("reason") String reason,@Param("at") Instant at);
    List<MemberPointsApi.Entry> ledger(@Param("tenant") String tenant,@Param("member") String member,@Param("after") long after,@Param("limit") int limit);
}
