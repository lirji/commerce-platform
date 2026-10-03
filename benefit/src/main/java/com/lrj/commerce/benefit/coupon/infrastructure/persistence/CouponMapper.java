package com.lrj.commerce.benefit.coupon.infrastructure.persistence;

import com.lrj.commerce.benefit.coupon.api.CouponApi.*;
import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;

/** 发券额度和钱包状态都由数据库条件更新决定。 */
@Mapper
public interface CouponMapper {

	record DefinitionRow(String definitionId, long version, String storeId, String name, String minimumSpend,
			String discountAmount, Instant validFrom, Instant validTo, int quota, boolean stackable, int issued,
			int platformFundingBps, String issuanceMode, Integer validityDays) {
	}

	void definition(@Param("tenant") String tenant, @Param("input") Definition input);

	DefinitionRow definitionFind(@Param("tenant") String tenant, @Param("id") String id,
			@Param("version") long version);

	DefinitionRow definitionLock(@Param("tenant") String tenant, @Param("id") String id,
			@Param("version") long version);

	List<DefinitionRow> definitions(@Param("tenant") String tenant, @Param("store") String store,
			@Param("after") String after, @Param("limit") int limit, @Param("admin") boolean admin, @org.apache.ibatis.annotations.Param("filter") com.lrj.commerce.runtime.api.validation.ListFilter filter);

	/** 兼容内部既有读取调用，缺省时不附加筛选条件。 */
	default List<DefinitionRow> definitions(@Param("tenant") String tenant, @Param("store") String store,
			@Param("after") String after, @Param("limit") int limit, @Param("admin") boolean admin) {
		return definitions(tenant, store, after, limit, admin, com.lrj.commerce.runtime.api.validation.ListFilter.none());
	}

	int issue(@Param("tenant") String tenant, @Param("id") String id, @Param("version") long version);

	int reserveCampaignQuota(@Param("tenant") String tenant, @Param("id") String id,
			@Param("version") long version);

	int releaseCampaignQuota(@Param("tenant") String tenant, @Param("id") String id,
			@Param("version") long version);

	int issueCampaignQuota(@Param("tenant") String tenant, @Param("id") String id,
			@Param("version") long version);

	void insertCampaignHold(@Param("tenant") String tenant, @Param("order") String order,
			@Param("member") String member, @Param("store") String store, @Param("ref") Ref ref,
			@Param("coupon") String coupon);

	CampaignHold campaignHold(@Param("tenant") String tenant, @Param("order") String order);

	CampaignHold lockCampaignHold(@Param("tenant") String tenant, @Param("order") String order);

	int campaignHoldStatus(@Param("tenant") String tenant, @Param("order") String order,
			@Param("expected") String expected, @Param("target") String target);

	Coupon bySource(@Param("tenant") String tenant, @Param("source") String source, @Param("type") String type);

	void sourceCoupon(@Param("tenant") String tenant, @Param("member") String member, @Param("id") String id,
			@Param("source") String source, @Param("type") String type, @Param("definition") DefinitionRow definition,
			@Param("from") Instant from, @Param("to") Instant to);

	Coupon existing(@Param("tenant") String tenant, @Param("member") String member, @Param("id") String id,
			@Param("version") long version);

	void coupon(@Param("tenant") String tenant, @Param("member") String member, @Param("id") String id,
			@Param("definition") DefinitionRow definition, @Param("from") Instant from, @Param("to") Instant to);

	Coupon find(@Param("tenant") String tenant, @Param("member") String member, @Param("id") String id);

	record LockedCoupon(String couponId, String definitionId, long version, String memberId, String status,
			Instant validTo, Instant validFrom) {
	}

	LockedCoupon lock(@Param("tenant") String tenant, @Param("member") String member, @Param("id") String id);

	List<Coupon> wallet(@Param("tenant") String tenant, @Param("member") String member, @Param("after") String after,
			@Param("limit") int limit, @org.apache.ibatis.annotations.Param("filter") com.lrj.commerce.runtime.api.validation.ListFilter filter);

	/** 兼容内部既有读取调用，缺省不附加筛选。 */
	default List<Coupon> wallet(@Param("tenant") String tenant, @Param("member") String member, @Param("after") String after,
			@Param("limit") int limit) {
		return wallet(tenant, member, after, limit, com.lrj.commerce.runtime.api.validation.ListFilter.none());
	}

	int revoke(@Param("tenant") String tenant, @Param("id") String id);

	int reserve(@Param("tenant") String tenant, @Param("id") String id, @Param("order") String order);

	void insertHold(@Param("tenant") String tenant, @Param("order") String order, @Param("id") String id,
			@Param("discount") String discount);

	record Hold(String couponId, String status) {
	}

	Hold hold(@Param("tenant") String tenant, @Param("order") String order);

	int finish(@Param("tenant") String tenant, @Param("order") String order, @Param("id") String id,
			@Param("expected") String expected, @Param("target") String target);

	int holdStatus(@Param("tenant") String tenant, @Param("order") String order, @Param("expected") String expected,
			@Param("target") String target);

}
