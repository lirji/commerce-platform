package com.lrj.commerce.journey.domain;

import com.lrj.commerce.journey.api.JourneyApi.*;
import com.lrj.commerce.campaign.rule.api.RuleNode;
import com.lrj.commerce.kernel.*;
import com.lrj.commerce.runtime.api.validation.Inputs;
import java.util.*;

/** 既有typed图的唯一结构校验入口；不读库、不评估业务事实、不执行动作。 */
public final class JourneyGraph {

	private JourneyGraph() {
	}

	/** 结构错误带稳定码，未知或越界配置不能进入运行时。 */
	public static Validation validate(Definition definition) {
		try {
			validateDefinition(definition);
			return new Validation(true, List.of());
		}
		catch (InvalidGraph failure) {
			return new Validation(false, List.of(new ValidationIssue(failure.code, null)));
		}
		catch (DomainException failure) {
			return new Validation(false, List.of(new ValidationIssue(ValidationCode.INVALID_DEFINITION, null)));
		}
	}

	/** 创建和发布复用同一检查，旧错误HTTP类别仍为INVALID_INPUT。 */
	public static void requireValid(Definition definition) {
		var checked = validate(definition);
		if (!checked.valid())
			throw new DomainException(DomainException.Code.INVALID_INPUT, checked.issues().getFirst().code().name());
	}

	/** yes/no固定单路径，不依赖Map顺序；UNKNOWN由调用方显式终止。 */
	public static String branch(Node node, com.lrj.commerce.marketing.api.Condition.Truth truth) {
		return switch (truth) {
			case MATCH -> node.yesNext();
			case NO_MATCH -> node.noNext();
			case UNKNOWN -> node.id();
		};
	}

	private static void trusted(RuleNode rule) {
		try {
			rule.requireTrustedFields();
		}
		catch (DomainException | IllegalArgumentException | NullPointerException failure) {
			throw new InvalidGraph(ValidationCode.INVALID_CONDITION);
		}
	}

	private static void check(boolean valid, ValidationCode code, String reason) {
		if (!valid)
			throw new InvalidGraph(code, reason);
	}

	private static final class InvalidGraph extends RuntimeException {
		private final ValidationCode code;
		private InvalidGraph(ValidationCode code) {
			this(code, code.name());
		}
		private InvalidGraph(ValidationCode code, String reason) {
			super(reason);
			this.code = code;
		}
	}

	private static boolean lifecycle(Trigger trigger) {
		return Set.of(Trigger.BIRTHDAY, Trigger.DORMANT, Trigger.REPURCHASE, Trigger.CART_ABANDONED).contains(trigger);
	}

	private static void validateDefinition(Definition d) {
		check(d != null && d.version() > 0 && d.trigger() != null && d.validFrom() != null
				&& d.validTo() != null && d.validTo().isAfter(d.validFrom()), ValidationCode.INVALID_DEFINITION, "旅程版本或生效窗口无效");
		Identifiers.require(d.journeyId());
		Identifiers.require(d.storeId());
		Inputs.text(d.name(), 128);
		check(d.maxDurationSeconds() >= 1 && d.maxDurationSeconds() <= 2592000 && d.nodes() != null
				&& !d.nodes().isEmpty() && d.nodes().size() <= 32, ValidationCode.INVALID_DEFINITION, "旅程节点或执行期限超限");
		check(Set.of(Trigger.MANUAL, Trigger.ORDER_PAID).contains(d.trigger()) || d.controls() != null,
				ValidationCode.INVALID_DEFINITION, "会员事件旅程必须配置频控");
		if (d.controls() != null) {
			var c = d.controls();
			check(c.maxEntries() >= 1 && c.maxEntries() <= 100 && c.notificationLimit() >= 1
					&& c.notificationLimit() <= 100 && c.entryWindowSeconds() >= 60 && c.entryWindowSeconds() <= 2592000
					&& c.notificationWindowSeconds() >= 60 && c.notificationWindowSeconds() <= 2592000, ValidationCode.INVALID_DEFINITION, "旅程频控参数无效");
			if (c.entryRule() != null)
				trusted(c.entryRule());
			if (d.trigger() == Trigger.SEGMENT_ENTERED)
				Identifiers.require(c.segmentId());
			else
				check(c.segmentId() == null, ValidationCode.INVALID_DEFINITION, "只有人群触发可绑定segmentId");
		}
		if (lifecycle(d.trigger())) {
			var l = d.lifecycle();
			check(l != null && l.thresholdDays() >= 1 && l.thresholdDays() <= 365 && l.cartDelaySeconds() >= 60
					&& l.cartDelaySeconds() <= 604800 && l.scanIntervalSeconds() >= 300
					&& l.scanIntervalSeconds() <= 86400 && l.conversionWindowDays() >= 1
					&& l.conversionWindowDays() <= 30, ValidationCode.INVALID_DEFINITION, "生命周期阈值/扫描/观察窗无效");
		}
		else
			check(d.lifecycle() == null, ValidationCode.INVALID_DEFINITION, "普通触发不接受生命周期参数");
		var graph = new HashMap<String, Node>();
		for (var n : d.nodes()) {
			check(n != null && n.kind() != null, ValidationCode.UNKNOWN_NODE_TYPE, "节点类型缺失");
			Identifiers.require(n.id());
			check(graph.put(n.id(), n) == null, ValidationCode.DUPLICATE_NODE, "节点标识重复");
			check(n.kind() == Kind.WAIT ? n.seconds() != null && n.seconds() >= 1 && n.seconds() <= 604800
					: n.seconds() == null, ValidationCode.INVALID_WAIT, "等待参数无效");
			check(n.kind() == Kind.DECIDE ? n.rule() != null && n.yesNext() != null && n.noNext() != null
					: n.rule() == null && n.yesNext() == null && n.noNext() == null, ValidationCode.INVALID_CONDITION, "分支参数无效");
			check(n.kind() == Kind.GRANT ? n.benefit() != null : n.benefit() == null, ValidationCode.UNSUPPORTED_ACTION, "权益参数无效");
			check(
					n.kind() == Kind.COUPON ? n.coupon() != null && n.coupon().version() > 0 : n.coupon() == null,
					ValidationCode.UNSUPPORTED_ACTION, "券节点引用无效");
			if (n.coupon() != null)
				Identifiers.require(n.coupon().definitionId());
			if (n.kind() == Kind.NOTIFY) {
				Inputs.text(n.title(), 128);
				Inputs.text(n.body(), 1000);
			}
			else
				check(n.title() == null && n.body() == null, ValidationCode.UNSUPPORTED_ACTION, "非触达节点不能携带内容");
			if (n.kind() == Kind.DECIDE)
				trusted(n.rule());
			check(n.kind() == Kind.END || n.kind() == Kind.DECIDE ? n.next() == null : n.next() != null,
					ValidationCode.INVALID_EDGE, "后继节点无效");
		}
		var visiting = new HashSet<String>();
		var visited = new HashSet<String>();
		check(d.entry() != null && graph.containsKey(d.entry()), ValidationCode.MISSING_START_NODE, "起点不存在");
		walk(d.entry(), graph, visiting, visited);
		check(visited.size() == graph.size(), ValidationCode.UNREACHABLE_NODE, "存在不可达节点");
	}

	private static void walk(String id, Map<String, Node> graph, Set<String> visiting, Set<String> visited) {
		check(id != null && graph.containsKey(id), ValidationCode.MISSING_TARGET, "引用节点不存在");
		if (visited.contains(id))
			return;
		check(visiting.add(id), ValidationCode.CYCLE_NOT_SUPPORTED, "旅程不能存在循环");
		var n = graph.get(id);
		if (n.kind() == Kind.DECIDE) {
			walk(n.yesNext(), graph, visiting, visited);
			walk(n.noNext(), graph, visiting, visited);
		}
		else if (n.kind() != Kind.END)
			walk(n.next(), graph, visiting, visited);
		visiting.remove(id);
		visited.add(id);
	}

}
