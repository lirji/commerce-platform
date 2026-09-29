package com.lrj.commerce.runtime.api.identity;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.kernel.Identifiers;

/** 由认证边界构造的业务身份，租户不能从请求体替换。 */
public record Actor(String tenantId, String actorId, Role role, Channel channel, String executionId) {
	/** 旧认证与历史快照没有执行引用，不能在中央路由下继续经营。 */
	public Actor(String tenantId, String actorId, Role role, Channel channel) {
		this(tenantId, actorId, role, channel, null);
	}

	public enum Channel {

		WEB, MINI_APP

	}

	/** 旧调用和旧快照保留默认网站渠道。 */
	public Actor(String tenantId, String actorId, Role role) {
		this(tenantId, actorId, role, Channel.WEB);
	}

	/** PLATFORM_OPERATOR只读取跨租户聚合运行指标，不是任何租户的管理员或会员；其tenantId只是凭据归属，平台接口不按它取数。 */
	public enum Role {

		ADMIN, MEMBER, OPERATOR, PLATFORM_OPERATOR

	}

	/**
	 * 能力按最小权限单独命名：平台运维只读跨租户聚合指标；租户管理员可在本租户内查看与恢复停止的后台工作、执行受安全门约束的历史重放。
	 * 恢复与重放只作用于凭据所属租户，平台运维没有任何租户恢复或重放能力，租户管理员没有任何跨租户能力。
	 */
	public enum Capability {

		EVENT_RUNTIME_METRICS_READ, RUNTIME_RECOVERY_READ, RUNTIME_RECOVERY_EXECUTE, RUNTIME_REPLAY_EXECUTE,
		MARKETING_ACTIVITY_READ, MARKETING_DRAFT_EDIT, MARKETING_REVIEW, MARKETING_PUBLISH, MARKETING_PAUSE,
		MARKETING_PREVIEW, MARKETING_EXECUTION_READ

	}

	public Actor {
		channel = channel == null ? Channel.WEB : channel;
		Identifiers.require(tenantId);
		Identifiers.require(actorId);
		if (executionId != null && !executionId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
			throw new DomainException(DomainException.Code.FORBIDDEN, "执行引用无效");
		if (role == null)
			throw new DomainException(DomainException.Code.FORBIDDEN, "身份角色无效");
	}

	/** 只有平台运维角色拥有平台能力；租户管理员只拥有本租户运行时恢复与重放能力；门店运营与会员没有运行时能力。 */
	public java.util.Set<Capability> capabilities() {
		return switch (role) {
			case PLATFORM_OPERATOR -> java.util.EnumSet.of(Capability.EVENT_RUNTIME_METRICS_READ);
			case ADMIN -> java.util.EnumSet.of(Capability.RUNTIME_RECOVERY_READ, Capability.RUNTIME_RECOVERY_EXECUTE,
					Capability.RUNTIME_REPLAY_EXECUTE, Capability.MARKETING_ACTIVITY_READ, Capability.MARKETING_DRAFT_EDIT,
					Capability.MARKETING_REVIEW, Capability.MARKETING_PUBLISH, Capability.MARKETING_PAUSE,
					Capability.MARKETING_PREVIEW, Capability.MARKETING_EXECUTION_READ);
			default -> java.util.EnumSet.noneOf(Capability.class);
		};
	}

	public void require(Capability capability) {
		if (!capabilities().contains(capability))
			throw new DomainException(DomainException.Code.FORBIDDEN, "缺少操作能力：" + capability);
	}

	/** 管理权限仍在用例层校验，不能只靠控制器或页面隐藏入口。 */
	public void requireAdmin() {
		if (role != Role.ADMIN)
			throw new DomainException(DomainException.Code.FORBIDDEN, "需要管理权限");
	}
}
