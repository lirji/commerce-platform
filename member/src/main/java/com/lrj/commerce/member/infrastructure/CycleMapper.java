package com.lrj.commerce.member.infrastructure;

import com.lrj.commerce.member.api.MemberCycleApi;
import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;

/** 周期策略、来源贡献和考核快照仅由会员域写入。 */
@Mapper
public interface CycleMapper {
    record PolicyRow(long version, String policyJson) { }
    record Due(String tenantId, String memberId) { }
    record Contribution(String memberId, Instant occurredAt, long contribution) { }
    void policy(@Param("tenant") String tenant, @Param("p") MemberCycleApi.Policy p, @Param("json") String json);
    PolicyRow effective(@Param("tenant") String tenant, @Param("at") Instant at);
    List<PolicyRow> policies(@Param("tenant") String tenant, @Param("after") long after, @Param("limit") int limit);
    Contribution contribution(@Param("tenant") String tenant, @Param("source") String source);
    void insertContribution(@Param("tenant") String tenant, @Param("member") String member, @Param("source") String source, @Param("at") Instant at, @Param("amount") long amount);
    int updateContribution(@Param("tenant") String tenant, @Param("source") String source, @Param("amount") long amount);
    long sum(@Param("tenant") String tenant, @Param("member") String member, @Param("from") Instant from, @Param("to") Instant to);
    MemberCycleApi.View view(@Param("tenant") String tenant, @Param("member") String member);
    void insertView(@Param("tenant") String tenant, @Param("v") MemberCycleApi.View view);
    int updateView(@Param("tenant") String tenant, @Param("v") MemberCycleApi.View view, @Param("expected") long expected);
    List<Due> due(@Param("at") Instant at, @Param("limit") int limit);
}
