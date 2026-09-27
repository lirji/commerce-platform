package com.lrj.commerce.campaign.execution.application;

import com.lrj.commerce.benefit.entitlement.api.EntitlementApi;
import com.lrj.commerce.campaign.asset.api.MarketingAssets;
import com.lrj.commerce.campaign.execution.api.CampaignExecutionApi;
import com.lrj.commerce.campaign.execution.infrastructure.persistence.CampaignExecutionMapper;
import com.lrj.commerce.campaign.management.api.CampaignApi;
import com.lrj.commerce.campaign.management.infrastructure.persistence.CampaignMapper;
import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.kernel.Identifiers;
import com.lrj.commerce.kernel.Money;
import com.lrj.commerce.marketing.api.DecisionModels;
import com.lrj.commerce.runtime.api.event.EventHandler;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.api.validation.Inputs;
import com.lrj.commerce.runtime.event.EventInspection;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 固定订单参与和履约投影；规则决定与额度权威仍由既有模块承担。 */
@Service
public class CampaignExecutionService implements CampaignExecutionApi, EventHandler {

	private record GrantAvailable(String orderId, String grantId) {
	}

	private final CampaignExecutionMapper executions;

	private final CampaignMapper campaigns;

	private final EntitlementApi entitlements;

	private final EventInspection events;

	public CampaignExecutionService(CampaignExecutionMapper executions, CampaignMapper campaigns,
			EntitlementApi entitlements, EventInspection events) {
		this.executions = executions;
		this.campaigns = campaigns;
		this.entitlements = entitlements;
		this.events = events;
	}

	/** 报价已由同一订单事务消费；重新取不可变版本只用于验证引用，不重新评价规则。 */
	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void recordOrder(Actor actor, OrderTrigger trigger) {
		Inputs.require(trigger != null, "营销订单触发缺失");
		if (trigger.campaign() == null)
			return;
		Identifiers.require(trigger.orderId());
		Identifiers.require(trigger.quoteId());
		Identifiers.require(trigger.memberId());
		Identifiers.require(trigger.storeId());
		Inputs.require(trigger.evaluatedAt() != null && trigger.trace() != null && trigger.trace().size() <= 100,
				"营销决定摘要无效");
		var selected = trigger.campaign();
		Identifiers.require(selected.campaignId());
		Inputs.require(selected.version() > 0, "活动版本无效");
		var trace = trigger.trace()
			.stream()
			.filter(item -> item.campaignId().equals(selected.campaignId()) && item.version() == selected.version())
			.findFirst()
			.orElseThrow(() -> new DomainException(DomainException.Code.CONFLICT, "报价缺少选中活动的决定证据"));
		Inputs.require(trace.reason() == DecisionModels.Reason.ELIGIBLE, "非合资格活动不能参与");
		Money discount;
		try {
			discount = new Money(new BigDecimal(trigger.campaignDiscount()));
		}
		catch (RuntimeException invalid) {
			throw new DomainException(DomainException.Code.INVALID_INPUT, "活动优惠金额无效");
		}
		Inputs.require(discount.compareTo(Money.ZERO) > 0, "活动优惠必须大于零");
		var activity = Inputs.found(campaigns.find(actor.tenantId(), selected.campaignId(), selected.version()));
		Inputs.require(activity.storeId().equals(trigger.storeId()), "报价活动店铺不匹配");
		CampaignApi.Policy policy = activity.policyJson() == null ? null
				: JsonCodec.read(activity.policyJson(), CampaignApi.Policy.class);
		MarketingAssets.Ref rule = policy == null ? null : policy.rule();
		MarketingAssets.Ref audience = policy == null ? null : policy.audience();
		EntitlementApi.Ref benefit = policy == null || policy.terms() == null ? null : policy.terms().grant();
		Inputs.require(Objects.equals(benefit, trigger.benefit()), "报价权益版本与活动不一致");
		if (audience != null) {
			Inputs.require(trigger.audiences() != null && trigger.audiences()
				.stream()
				.anyMatch(source -> source.audienceId().equals(audience.id()) && source.version() == audience.version()
						&& source.match().equals("HIT")), "报价缺少人群命中证据");
		}
		var grant = entitlements.orderGrant(actor.tenantId(), trigger.orderId());
		Inputs.require(benefit == null ? grant == null
				: grant != null && grant.benefitId().equals(benefit.benefitId())
						&& grant.benefitVersion() == benefit.version() && grant.memberId().equals(trigger.memberId()),
				"权益预留与营销决定不一致");
		var row = new CampaignExecutionMapper.Row(trigger.orderId(), selected.campaignId(), selected.version(),
				trigger.quoteId(), trigger.memberId(), trigger.storeId(), rule == null ? null : rule.id(),
				rule == null ? null : rule.version(), audience == null ? null : audience.id(),
				audience == null ? null : audience.version(), benefit == null ? null : benefit.benefitId(),
				benefit == null ? null : benefit.version(), grant == null ? null : grant.grantId(),
				discount.amount().toPlainString(), trace.reason().name(), "RESERVED", trigger.evaluatedAt(), null,
				null, 0);
		executions.insert(actor.tenantId(), row);
	}

	/** 与支付或取消提交同事务；没有选中活动的订单无营销参与记录。 */
	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void settleOrder(String tenant, String orderId, boolean paid) {
		Identifiers.require(tenant);
		Identifiers.require(orderId);
		for (var row : executions.byOrder(tenant, orderId)) {
			if (row.status().equals("RESERVED")) {
				int updated = paid ? executions.paid(tenant, orderId, row.campaignId())
						: executions.released(tenant, orderId, row.campaignId());
				if (updated != 1)
					throw new DomainException(DomainException.Code.CONFLICT, "营销参与并发状态冲突");
			}
			else if (paid && Set.of("GRANT_REQUESTED", "GRANTED", "APPLIED").contains(row.status())) {
				// 支付确认重投递已被订单状态去重；此处保持幂等以兼容历史调用。
			}
			else if (!paid && row.status().equals("RELEASED")) {
				// 同一取消事实重复到达时不写第二次状态。
			}
			else
				throw new DomainException(DomainException.Code.CONFLICT, "营销参与状态与订单结果冲突");
		}
	}

	/** 精确详情将持久参与、权益权威状态与运行时失败证据合并，不触发重试。 */
	@Override
	public View find(Actor actor, String orderId, String campaignId) {
		actor.require(Actor.Capability.MARKETING_EXECUTION_READ);
		Identifiers.require(orderId);
		Identifiers.require(campaignId);
		return view(Inputs.found(executions.find(actor.tenantId(), orderId, campaignId)), actor.tenantId(), true);
	}

	/** 列表只查询本租户审计表，避免逐行加载权益和事件导致N+1。 */
	@Override
	public List<View> list(Actor actor, String afterOrder, int limit) {
		actor.require(Actor.Capability.MARKETING_EXECUTION_READ);
		Inputs.page(afterOrder, limit);
		return executions.list(actor.tenantId(), afterOrder, limit)
			.stream()
			.map(row -> view(row, actor.tenantId(), false))
			.toList();
	}

	private View view(CampaignExecutionMapper.Row row, String tenant, boolean detail) {
		var grant = detail && row.grantId() != null ? entitlements.orderGrant(tenant, row.orderId()) : null;
		var event = detail && row.grantId() != null
				? events.latest(tenant, "benefit.grant.requested.v1", row.grantId()) : null;
		String status = row.status();
		if (status.equals("GRANT_REQUESTED") && grant != null
				&& Set.of(EntitlementApi.State.AVAILABLE, EntitlementApi.State.CONSUMED,
						EntitlementApi.State.REVOKED, EntitlementApi.State.COMPENSATION_REQUIRED,
						EntitlementApi.State.COMPENSATED).contains(grant.status()))
			status = "GRANTED";
		else if (status.equals("GRANT_REQUESTED") && event != null
				&& Set.of("ISOLATED", "SKIPPED").contains(event.status()))
			status = "GRANT_FAILED";
		return new View(row.orderId(), row.campaignId(), row.campaignVersion(), row.quoteId(), row.memberId(),
				row.storeId(), row.ruleId(), row.ruleVersion(), row.audienceId(), row.audienceVersion(), row.benefitId(),
				row.benefitVersion(), row.grantId(), row.discountAmount(), row.reasonCode(), status,
				grant == null ? null : grant.status().getCode(), event == null ? null : event.eventId(),
				event == null ? null : event.status(), event == null ? null : event.failureClass(), row.evaluatedAt(),
				row.createdAt(), row.updatedAt(), row.lockVersion());
	}

	@Override
	public String consumer() {
		return "marketing-execution-projection-v1";
	}

	@Override
	public Set<String> types() {
		return Set.of("benefit.grant.available.v1");
	}

	/** 只更新已固定的真实grant；旧订单无执行记录时保持向前兼容。 */
	@Override
	public void handle(Event event) {
		var fact = JsonCodec.read(event.payloadJson(), GrantAvailable.class);
		Identifiers.require(fact.orderId());
		Identifiers.require(fact.grantId());
		var rows = executions.byOrder(event.tenantId(), fact.orderId());
		if (rows.isEmpty())
			return;
		var row = rows.stream()
			.filter(candidate -> fact.grantId().equals(candidate.grantId()))
			.findFirst()
			.orElseThrow(() -> new DomainException(DomainException.Code.CONFLICT, "权益事件与营销执行不匹配"));
		if (row.status().equals("GRANTED"))
			return;
		if (!row.status().equals("GRANT_REQUESTED")
				|| executions.granted(event.tenantId(), fact.orderId(), fact.grantId()) != 1)
			throw new DomainException(DomainException.Code.CONFLICT, "营销权益结果状态冲突");
	}

	@Override
	public ReplaySafety replaySafety() {
		return ReplaySafety.notReplayable(
				"只更新固定grant的GRANT_REQUESTED参与记录，已GRANTED重复投递无效；历史重放不扩大营销执行范围",
				SideEffect.IDEMPOTENT_WRITE);
	}

}
