package com.lrj.commerce.runtime.api.validation;

import java.time.Instant;

/** 有界只读筛选值；字段和 SQL 由各 Owner 显式决定，不能传入列名或排序表达式。 */
public record ListFilter(String q, String status, Instant from, Instant to, Boolean enabled) {

	/** 统一清理空白并拒绝无界输入；时间使用半开区间，避免相邻范围重复。 */
	public ListFilter {
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
