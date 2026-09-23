package com.lrj.commerce.member.infrastructure;
import com.lrj.commerce.member.api.*;
import org.apache.ibatis.annotations.*;
import java.util.List;
import java.time.Instant;

/** 成长账本和来源净额由会员域独占；会员行锁统一同会员操作顺序。 */
@Mapper
public interface GrowthMapper {
 record PolicyRow(long version,Instant effectiveFrom,String policyJson) { }
 record Account(long growth,String netSpend,long policyVersion,long version) { }
 record Source(String orderId,String memberId,String paid,boolean completed,long policyVersion,String growthRate,long contribution,String netSpend) { }
 record Refund(String orderId,String amount) { }
 void policy(@Param("tenant") String tenant,@Param("input") MemberGrowthApi.Policy input,@Param("json") String json);
 PolicyRow effective(@Param("tenant") String tenant,@Param("at") Instant at);
 List<PolicyRow> policies(@Param("tenant") String tenant,@Param("after") long after,@Param("limit") int limit);
 MemberApi.View lockMember(@Param("tenant") String tenant,@Param("member") String member);
 void ensureAccount(@Param("tenant") String tenant,@Param("member") String member);
 Account account(@Param("tenant") String tenant,@Param("member") String member);
 int accountChange(@Param("tenant") String tenant,@Param("member") String member,@Param("growth") long growth,@Param("net") String net,@Param("policy") long policy,@Param("version") long version);
 void level(@Param("tenant") String tenant,@Param("member") String member,@Param("level") String level);
 Source source(@Param("tenant") String tenant,@Param("order") String order);
 void insertSource(@Param("tenant") String tenant,@Param("fact") MemberGrowthApi.OrderFact fact,@Param("policy") long policy,@Param("rate") String rate);
 void updateSource(@Param("tenant") String tenant,@Param("order") String order,@Param("completed") boolean completed,@Param("contribution") long contribution,@Param("net") String net);
 Refund refund(@Param("tenant") String tenant,@Param("id") String id);
 void insertRefund(@Param("tenant") String tenant,@Param("fact") MemberGrowthApi.OrderFact fact);
 String refunds(@Param("tenant") String tenant,@Param("order") String order);
 void entry(@Param("tenant") String tenant,@Param("member") String member,@Param("source") String source,@Param("delta") long delta,@Param("balance") long balance,@Param("policy") long policy,@Param("reason") String reason);
 List<MemberGrowthApi.Entry> ledger(@Param("tenant") String tenant,@Param("member") String member,@Param("after") long after,@Param("limit") int limit);
 List<String> tags(@Param("tenant") String tenant,@Param("member") String member);
 void tag(@Param("tenant") String tenant,@Param("input") MemberTagApi.Definition input);
 MemberTagApi.Definition tagDefinition(@Param("tenant") String tenant,@Param("id") String id);
 List<MemberTagApi.Definition> tagDefinitions(@Param("tenant") String tenant,@Param("after") String after,@Param("limit") int limit);
 MemberTagApi.Assignment assignment(@Param("tenant") String tenant,@Param("member") String member,@Param("tag") String tag);
 void assign(@Param("tenant") String tenant,@Param("member") String member,@Param("input") MemberTagApi.Assign input);
 List<MemberTagApi.Assignment> assignments(@Param("tenant") String tenant,@Param("member") String member,@Param("after") String after,@Param("limit") int limit);
}
