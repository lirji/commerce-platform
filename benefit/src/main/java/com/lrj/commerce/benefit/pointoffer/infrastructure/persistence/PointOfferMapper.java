package com.lrj.commerce.benefit.pointoffer.infrastructure.persistence;

import com.lrj.commerce.benefit.pointoffer.api.PointOfferApi.*;
import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;

/** 目录锁约束总额度，会员计数不扫描历史回执。 */
@Mapper
public interface PointOfferMapper {

	record Row(String contentJson, String status, int issued, long version) {
	}

	void insert(@Param("tenant") String tenant, @Param("input") Offer input, @Param("json") String json);

	Row find(@Param("tenant") String tenant, @Param("id") String id);

	Row lock(@Param("tenant") String tenant, @Param("id") String id);

	List<Row> list(@Param("tenant") String tenant, @Param("store") String store, @Param("after") String after,
			@Param("limit") int limit, @Param("admin") boolean admin, @Param("now") Instant now, @org.apache.ibatis.annotations.Param("filter") com.lrj.commerce.runtime.api.validation.ListFilter filter);

	/** 兼容内部既有读取调用，缺省时不附加筛选条件。 */
	default List<Row> list(@Param("tenant") String tenant, @Param("store") String store, @Param("after") String after,
			@Param("limit") int limit, @Param("admin") boolean admin, @Param("now") Instant now) {
		return list(tenant, store, after, limit, admin, now, com.lrj.commerce.runtime.api.validation.ListFilter.none());
	}

	int status(@Param("tenant") String tenant, @Param("id") String id, @Param("input") Status input);

	int issue(@Param("tenant") String tenant, @Param("id") String id);

	Integer count(@Param("tenant") String tenant, @Param("id") String id, @Param("member") String member);

	void memberIssue(@Param("tenant") String tenant, @Param("id") String id, @Param("member") String member);

	void receipt(@Param("tenant") String tenant, @Param("input") Receipt input);

	List<Receipt> receipts(@Param("tenant") String tenant, @Param("member") String member, @Param("after") String after,
			@Param("limit") int limit);

}
