package com.lrj.commerce.member.cycle.infrastructure.persistence;

import com.lrj.commerce.member.cycle.api.MemberCycleApi;
import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;

/** 周期策略、来源贡献和考核快照仅由会员域写入。 */
@Mapper
public interface CycleMapper {

	record PolicyRow(long version, String policyJson) {
	}

	/** attempts与transientAttempts来自逐项重试行，从未失败时为null。 */
	record Due(String tenantId, String memberId, Integer attempts, Integer transientAttempts) {
	}

	/** 尚未把全部会员到期时间拉到生效时间的策略；cursor为已推进到的最后会员标识。 */
	record Rollout(long version, Instant effectiveFrom, String rolloutCursor) {
	}

	record Backlog(long due, Instant oldestDue) {
	}

	record Contribution(String memberId, Instant occurredAt, long contribution) {
	}

	void policy(@Param("tenant") String tenant, @Param("p") MemberCycleApi.Policy p, @Param("json") String json);

	PolicyRow byVersion(@Param("tenant") String tenant, @Param("version") long version);

	PolicyRow effective(@Param("tenant") String tenant, @Param("at") Instant at);

	List<PolicyRow> policies(@Param("tenant") String tenant, @Param("after") long after, @Param("limit") int limit);

	Contribution contribution(@Param("tenant") String tenant, @Param("source") String source);

	void insertContribution(@Param("tenant") String tenant, @Param("member") String member,
			@Param("source") String source, @Param("at") Instant at, @Param("amount") long amount);

	int updateContribution(@Param("tenant") String tenant, @Param("source") String source,
			@Param("amount") long amount);

	long sum(@Param("tenant") String tenant, @Param("member") String member, @Param("from") Instant from,
			@Param("to") Instant to);

	MemberCycleApi.View view(@Param("tenant") String tenant, @Param("member") String member);

	void insertView(@Param("tenant") String tenant, @Param("v") MemberCycleApi.View view);

	int updateView(@Param("tenant") String tenant, @Param("v") MemberCycleApi.View view,
			@Param("expected") long expected);

	List<Due> due(@Param("tenant") String tenant, @Param("at") Instant at, @Param("limit") int limit);

	List<String> dueTenants(@Param("after") String after, @Param("at") Instant at, @Param("limit") int limit);

	/** 注销会员的到期时间：永不到期；列不为空，使(租户,到期)索引的第一项就是该租户最早的到期时间。 */
	Instant NEVER = Instant.parse("9999-12-31T00:00:00Z");

	/** 考核后写入下次到期时间；注销会员写NEVER。 */
	int schedule(@Param("tenant") String tenant, @Param("member") String member, @Param("due") Instant due);

	/** 生效时间晚于at的最早策略，用于把下次到期限定在策略切换之前。 */
	Instant nextPolicyStart(@Param("tenant") String tenant, @Param("at") Instant at);

	Rollout pendingRollout(@Param("tenant") String tenant);

	List<String> memberPage(@Param("tenant") String tenant, @Param("after") String after, @Param("limit") int limit);

	/** 把(from,to]区间内晚于生效时间的会员到期时间拉到生效时间；注销会员（NEVER）不变。 */
	int pullDue(@Param("tenant") String tenant, @Param("from") String from, @Param("to") String to,
			@Param("due") Instant due);

	/** 游标比较交换推进，多实例同时推进同一批时只有一个提交。 */
	int rolloutProgress(@Param("tenant") String tenant, @Param("version") long version,
			@Param("expected") String expected, @Param("cursor") String cursor, @Param("done") boolean done);

	Backlog backlog(@Param("at") Instant at);

	/** 会员是否仍需考核（恢复时判断隔离项是否已由其他路径完成考核）。 */
	boolean assessmentDue(@Param("tenant") String tenant, @Param("member") String member, @Param("at") Instant at);

}
