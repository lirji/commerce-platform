package com.lrj.commerce.runtime.api.validation;

import com.lrj.commerce.kernel.Identifiers;
import java.time.Instant;

/** 有界只读筛选值；字段和 SQL 由各 Owner 显式决定，不能传入列名或排序表达式。 */
public record ListFilter(String q, String status, Instant from, Instant to, Boolean enabled,
		String storeId, String orderId, Boolean blocked, String merchantId, String campaignId, String aggregateId) {

	/** 统一清理空白并拒绝无界输入；时间使用半开区间，避免相邻范围重复。 */
	public ListFilter {
		if (storeId != null) Identifiers.require(storeId);
		if (orderId != null) Identifiers.require(orderId);
		if (merchantId != null) Identifiers.require(merchantId);
		if (campaignId != null) Identifiers.require(campaignId);
		if (aggregateId != null) Identifiers.require(aggregateId);
		q = normalize(q);
		status = normalize(status);
		Inputs.require(q == null || q.length() <= 64, "查询关键词最多64个字符");
		Inputs.require(status == null || status.matches("[A-Z_]{1,40}"), "查询状态无效");
		Inputs.require(from == null || to == null || from.isBefore(to), "查询开始时间须早于结束时间");
	}

	/** 大多数列表没有独立启用标志，四参调用维持兼容。 */
	public ListFilter(String q, String status, Instant from, Instant to) {
		this(q, status, from, to, null);
	}

	/** 兼容原启用状态筛选调用。 */
	public ListFilter(String q, String status, Instant from, Instant to, Boolean enabled) {
		this(q, status, from, to, enabled, null, null, null, null, null, null);
	}

	/** 交易条件只会缩小 Owner 原有授权范围，精确标识在 SQL 分页前应用。 */
	public static ListFilter trade(String q, String status, Instant from, Instant to,
			String storeId, String orderId, Boolean blocked) {
		return new ListFilter(q, status, from, to, null, storeId, orderId, blocked, null, null, null);
	}

	/** 目录关联使用实际商家与活动编号，在目标 Owner 权限范围内检索。 */
	public static ListFilter related(String q, String status, Instant from, Instant to,
			String merchantId, String campaignId) {
		return new ListFilter(q, status, from, to, null, null, null, null, merchantId, campaignId, null);
	}

	/** 聚合编号不参与模糊匹配，事件类型仍由原记录标明。 */
	public static ListFilter events(String q, String status, String aggregateId) {
		return new ListFilter(q, status, null, null, null, null, null, null, null, null, aggregateId);
	}

	/** 仅明确支持启用标志的 Owner 可以使用该条件。 */
	public void requireNoEnabled() {
		Inputs.require(enabled == null, "此列表不支持启用状态筛选");
	}

	/** 旧 API 调用不附加查询条件，保持原有语义。 */
	public static ListFilter none() {
		return new ListFilter(null, null, null, null);
	}

	/** 没有时间事实的目录不能静默忽略时间筛选。 */
	public void requireNoTime() {
		Inputs.require(from == null && to == null, "此列表不支持时间筛选");
	}

	/** 无生命周期状态的主数据不能伪造状态筛选。 */
	public void requireNoStatus() {
		Inputs.require(status == null, "此列表不支持状态筛选");
	}

	private static String normalize(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
