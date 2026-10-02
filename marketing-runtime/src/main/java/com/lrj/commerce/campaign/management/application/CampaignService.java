package com.lrj.commerce.campaign.management.application;

import com.lrj.commerce.campaign.funding.infrastructure.persistence.BudgetMapper;
import com.lrj.commerce.campaign.management.infrastructure.persistence.CampaignMapper;
import com.lrj.commerce.marketing.api.DecisionModels.*;
import com.lrj.commerce.store.management.api.StoreApi;
import com.lrj.commerce.kernel.*;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import com.lrj.commerce.campaign.asset.api.MarketingAssets;
import com.lrj.commerce.campaign.management.api.CampaignApi;
import com.lrj.commerce.campaign.rule.api.MemberRuleFacts;
import com.lrj.commerce.campaign.rule.api.RuleNode;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import static com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.*;
import com.lrj.commerce.runtime.api.validation.Inputs;
import com.lrj.commerce.runtime.command.Commands;
import com.lrj.commerce.runtime.serialization.JsonCodec;

/** 发布切换与审计同事务；旧版本不会在切换中和新版本同时生效。 */
@Service
public class CampaignService implements CampaignApi {

	private final com.lrj.commerce.member.profile.api.MemberApi members;

	private final com.lrj.commerce.member.growth.api.MemberGrowthApi memberGrowth;

	private final com.lrj.commerce.catalog.assortment.api.CatalogApi catalog;

	private final com.lrj.commerce.marketing.api.DecisionPort decisions;

	private final com.lrj.commerce.benefit.entitlement.api.EntitlementApi entitlements;
	private final com.lrj.commerce.benefit.coupon.api.CouponApi coupons;
	private final boolean couponEnabled;

	private final com.lrj.commerce.campaign.funding.infrastructure.persistence.BudgetMapper budgets;

	private final MarketingAssets assets;

	private final java.time.Clock clock;

	private final CampaignMapper mapper;

	private final StoreApi stores;

	private final Commands commands;

	private final EmployeeAccess access;

	public CampaignService(CampaignMapper mapper, StoreApi stores, Commands commands, MarketingAssets assets,
			java.time.Clock clock, com.lrj.commerce.campaign.funding.infrastructure.persistence.BudgetMapper budgets,
			com.lrj.commerce.benefit.entitlement.api.EntitlementApi entitlements,
			com.lrj.commerce.member.profile.api.MemberApi members,
			com.lrj.commerce.member.growth.api.MemberGrowthApi memberGrowth,
			com.lrj.commerce.catalog.assortment.api.CatalogApi catalog,
			com.lrj.commerce.marketing.api.DecisionPort decisions,
			com.lrj.commerce.benefit.coupon.api.CouponApi coupons,
			@org.springframework.beans.factory.annotation.Value("${commerce.marketing.coupon-enabled:false}") boolean couponEnabled,
			EmployeeAccess access) {
		this.access = access;
		this.members = members;
		this.memberGrowth = memberGrowth;
		this.catalog = catalog;
		this.decisions = decisions;
		this.entitlements = entitlements;
		this.coupons = coupons;
		this.couponEnabled = couponEnabled;
		this.budgets = budgets;
		this.assets = assets;
		this.clock = clock;
		this.mapper = mapper;
		this.stores = stores;
		this.commands = commands;
	}

	/** 草稿内容不可变，修改必须创建新版本。 */
	public View create(Actor actor, String key, Draft input) {
		return prepareCreate(actor, key, input).execute();
	}

	/** 组合页面不得在持有页面/业务锁时远程判权；冻结深层输入与真实门店版本。 */
	public PreparedCreation prepareCreate(Actor actor, String key, Draft requested) {
		Inputs.require(requested != null, "请求不能为空");
		final var input = com.lrj.commerce.runtime.serialization.JsonCodec.read(
				com.lrj.commerce.runtime.serialization.JsonCodec.write(requested), Draft.class);
		var permit = access.scope(actor, CAMPAIGN_CREATE);
		Inputs.require(input != null, "请求不能为空");
		Identifiers.require(input.campaignId());
		Inputs.text(input.name(), 128);
		Inputs.require(
				input.version() > 0 && input.validFrom() != null && input.validTo() != null
						&& input.validFrom().isBefore(input.validTo())
						&& (input.rule() != null || (input.policy() != null && input.policy().rule() != null)),
				"活动版本无效");
		if (input.rule() != null)
			input.rule().requireTrustedFields();
		if (input.policy() != null)
			Inputs.require(
					input.policy().audience() != null || input.policy().rule() != null
							|| (input.policy().terms() != null && input.policy().terms().pricing() != null),
					"受治理活动需引用可信资产或声明精细价格策略");
		if (input.policy() != null && input.policy().terms() != null) {
			var terms = input.policy().terms();
			validateBenefitChoice(terms);
			pricing(terms.pricing());
			Inputs.require(terms.percentageBps() >= 0 && terms.percentageBps() <= 10000
					&& terms.platformFundingBps() >= 0 && terms.platformFundingBps() <= 10000, "活动百分比参数无效");
			if (terms.budget() != null)
				Inputs.require(money(terms.budget()).compareTo(Money.ZERO) > 0, "活动预算必须大于零");
		}
		money(input.minimumSpend());
		Inputs.require(money(input.discountAmount()).compareTo(Money.ZERO) > 0, "优惠必须大于零");
		// 旧模式保留原摘要；中央只加入稳定主体，执行nonce不参与重试意图。
		Object command = permit.identity() == null ? input : new Object[] { input, permit.identity() };
		// 预读只选历史方向，最终仍由当前身份栅栏和原命令锁决定，绝不事务外返回回执。
		var completed = completedCreation(actor, key, command, permit);
		if (completed != null) return completed;
		final StoreApi.View preparedStore;
		try {
			preparedStore = stores.requireActive(actor, input.storeId());
		} catch (DomainException unavailableStore) {
			// 另一请求可在预读后完成并冻结/删除门店；只对业务资源失败补读一次，不吞权限或系统异常。
			if (unavailableStore.code() != DomainException.Code.CONFLICT
					&& unavailableStore.code() != DomainException.Code.NOT_FOUND) throw unavailableStore;
			var concurrentReceipt = completedCreation(actor, key, command, permit);
			if (concurrentReceipt == null) throw unavailableStore;
			return concurrentReceipt;
		}
		return () -> commands.runGuarded(actor, "campaign.create", key, command, View.class,
				() -> access.lock(permit), () -> {
			// 只在首次效果执行时核对已冻结的 ACTIVE 门店；并发先完成的原回执不重复验证新交易前提。
			stores.lockCurrent(actor, preparedStore);
			var store = preparedStore;
			RuleNode rule = input.rule();
			if (input.policy() != null) {
				if (input.policy().rule() != null)
					rule = assets.publishedRule(actor.tenantId(), input.policy().rule());
				if (input.policy().terms() != null && input.policy().terms().grant() != null)
					entitlements.validateBinding(actor.tenantId(), input.storeId(), input.policy().terms().grant(),
							input.validFrom(), input.validTo());
				if (input.policy().terms() != null && input.policy().terms().coupon() != null)
					coupons.validateCampaignBinding(actor.tenantId(), input.storeId(), input.policy().terms().coupon(),
							input.validFrom(), input.validTo());
				if (input.policy().audience() != null)
					assets.requireFresh(actor.tenantId(), input.policy().audience(), clock.instant());
			}
			mapper.insert(actor.tenantId(), store.merchantId(), input, JsonCodec.write(rule),
					input.policy() == null ? null : JsonCodec.write(input.policy()));
			budgets.create(actor.tenantId(), java.util.UUID.randomUUID().toString(), input.campaignId(),
					input.version(),
					input.policy() == null || input.policy().terms() == null ? null : input.policy().terms().budget());
			access.auditVersion(actor, permit, "campaign.create", key, input.campaignId(), input.version());
			return view(mapper.find(actor.tenantId(), input.campaignId(), input.version()));
		});
	}

	/** 历史回执不要求旧门店仍可参与新交易；回执并发清理时禁止降级为新的实际创建。 */
	private PreparedCreation completedCreation(Actor actor, String key, Object command, EmployeeAccess.ScopePermit permit) {
		if (commands.completedReceipt(actor, "campaign.create", key, command, View.class) == null) return null;
		return () -> commands.runGuarded(actor, "campaign.create", key, command, View.class,
				() -> access.lock(permit), () -> {
					throw new DomainException(DomainException.Code.CONFLICT, "原命令回执已清理，请核验原意图");
				});
	}

	/** 发布前锁定同活动全部版本，唯一约束继续承担最终完整性。 */
	public View publish(Actor actor, String key, String id, long version, long expected) {
		return change(actor, key, id, version, expected, true);
	}

	/** 暂停只影响新报价，历史报价快照不重写。 */
	public View pause(Actor actor, String key, String id, long version, long expected) {
		return change(actor, key, id, version, expected, false);
	}

	private View change(Actor actor, String key, String id, long version, long expected, boolean publish) {
		var scope = access.scope(actor, publish ? CAMPAIGN_PUBLISH : CAMPAIGN_PAUSE);
		Identifiers.require(id);
		Inputs.require(version > 0 && expected >= 0, "活动版本无效");
		var current = Inputs.found(mapper.find(actor.tenantId(), id, version));
		var permit = access.resource(actor, scope, fact(current));
		String operation = publish ? "campaign.publish" : "campaign.pause";
		Object command = command(scope, id, version, expected);
		return commands.runGuarded(actor, operation, key, command,
				View.class, () -> lockPermit(actor, permit), () -> {
					var group = mapper.lockGroup(actor.tenantId(), id);
					var row = group.stream()
						.filter(r -> r.version() == version)
						.findFirst()
						.orElseThrow(() -> new DomainException(DomainException.Code.NOT_FOUND, "活动版本不存在"));
					if (row.lockVersion() != expected || (publish && row.status().equals("PUBLISHED"))
							|| (!publish && !row.status().equals("PUBLISHED")))
						throw new DomainException(DomainException.Code.CONFLICT, "活动状态或版本冲突");
					if (publish)
						validatePublication(actor.tenantId(), row);
					stores.requireActive(actor, row.storeId());
					if (publish)
						mapper.pauseOthers(actor.tenantId(), id);
					if (mapper.change(actor.tenantId(), id, version, expected, publish ? "PUBLISHED" : "PAUSED") != 1)
						throw new DomainException(DomainException.Code.CONFLICT, "活动并发版本冲突");
					access.auditVersion(actor, permit.scope(), operation, key, row.campaignId(), row.version());
					return view(mapper.find(actor.tenantId(), id, version));
				});
	}

	/** 内容版本不改写；每次激活都复核固定引用和当前时间，避免恢复旧版时绕过发布门。 */
	private void validatePublication(String tenant, CampaignMapper.Row row) {
		Inputs.require(row.validFrom().isBefore(row.validTo()) && clock.instant().isBefore(row.validTo()),
				"活动有效期已结束或无效");
		JsonCodec.read(row.ruleJson(), RuleNode.class).requireTrustedFields();
		if (row.policyJson() == null)
			return;
		if (!java.util.Set.of("APPROVED", "PAUSED").contains(row.status()))
			throw new DomainException(DomainException.Code.CONFLICT, "受治理活动必须审批后发布");
		var policy = JsonCodec.read(row.policyJson(), Policy.class);
		if (policy.audience() != null)
			assets.requireFresh(tenant, policy.audience(), clock.instant());
		if (policy.rule() != null) {
			var publishedRule = assets.publishedRule(tenant, policy.rule());
			Inputs.require(publishedRule.equals(JsonCodec.read(row.ruleJson(), RuleNode.class)),
					"活动固定规则与引用版本不一致");
		}
		if (policy.terms() != null) {
			var terms = policy.terms();
			validateBenefitChoice(terms);
			pricing(terms.pricing());
			Inputs.require(terms.percentageBps() >= 0 && terms.percentageBps() <= 10000
					&& terms.platformFundingBps() >= 0 && terms.platformFundingBps() <= 10000,
					"活动百分比参数无效");
			if (terms.budget() != null)
				Inputs.require(money(terms.budget()).compareTo(Money.ZERO) > 0, "活动预算必须大于零");
			if (terms.grant() != null)
				entitlements.validateBinding(tenant, row.storeId(), terms.grant(), row.validFrom(), row.validTo());
			if (terms.coupon() != null)
				coupons.validateCampaignBinding(tenant, row.storeId(), terms.coupon(), row.validFrom(), row.validTo());
		}
	}

	/** 新券字段会使旧二进制严格 JSON 解码失败；全部旧节点退出前拒绝写入新配置。 */
	private void validateBenefitChoice(Terms terms) {
		Inputs.require(terms.grant() == null || terms.coupon() == null, "同一活动只能选择一种权益");
		if (terms.coupon() != null && !couponEnabled)
			throw new DomainException(DomainException.Code.CONFLICT, "活动券能力尚未完成全节点升级");
	}

	/** 管理台按活动ID列出每个活动最新内容版本。 */
	public List<View> list(Actor actor, String after, int limit) {
		var permit = access.scope(actor, CAMPAIGN_READ);
		Inputs.page(after, limit);
		var result = mapper.list(actor.tenantId(), after, limit).stream().map(this::view).toList();
		EmployeeAccess.requireSame(permit, access.scope(actor, CAMPAIGN_READ));
		return result;
	}

	private View view(CampaignMapper.Row r) {
		return new View(
				new Draft(r.campaignId(), r.version(), r.storeId(), r.name(), r.validFrom(), r.validTo(),
						r.minimumSpend(), r.discountAmount(), JsonCodec.read(r.ruleJson(), RuleNode.class),
						r.policyJson() == null ? null : JsonCodec.read(r.policyJson(), Policy.class)),
				r.merchantId(), r.status(), r.lockVersion());
	}

	private Money money(String value) {
		Inputs.text(value, 32);
		try {
			return new Money(new BigDecimal(value));
		}
		catch (NumberFormatException ex) {
			throw new DomainException(DomainException.Code.INVALID_INPUT, "金额格式无效");
		}
	}

	/** 每次审批变更都带预期版本并写命令审计，不能跳级发布。 */
	public View review(Actor actor, String key, String id, long version, long expected, String action) {
		Inputs.require(action != null && java.util.Set.of("submit", "approve", "reject").contains(action), "审批动作无效");
		var scope = access.scope(actor, switch (action) {
			case "submit" -> CAMPAIGN_SUBMIT;
			case "approve" -> CAMPAIGN_APPROVE;
			case "reject" -> CAMPAIGN_REJECT;
			default -> throw new IllegalArgumentException("未登记审批动作");
		});
		Identifiers.require(id);
		Inputs.require(version > 0 && expected >= 0 && java.util.Set.of("submit", "approve", "reject").contains(action),
				"审批动作无效");
		var current = Inputs.found(mapper.find(actor.tenantId(), id, version));
		var permit = access.resource(actor, scope, fact(current));
		String operation = "campaign." + action;
		return commands.runGuarded(actor, operation, key, command(scope, id, version, expected), View.class,
				() -> lockPermit(actor, permit), () -> {
			var row = mapper.lockGroup(actor.tenantId(), id)
				.stream()
				.filter(r -> r.version() == version)
				.findFirst()
				.orElseThrow(() -> new DomainException(DomainException.Code.NOT_FOUND, "活动版本不存在"));
			String required = action.equals("submit") ? "DRAFT" : "IN_REVIEW";
			String next = action.equals("submit") ? "IN_REVIEW" : action.equals("approve") ? "APPROVED" : "REJECTED";
			if (row.policyJson() == null || !row.status().equals(required) || row.lockVersion() != expected)
				throw new DomainException(DomainException.Code.CONFLICT, "审批状态或版本冲突");
			if (mapper.change(actor.tenantId(), id, version, expected, next) != 1)
				throw new DomainException(DomainException.Code.CONFLICT, "活动审批并发冲突");
			access.auditVersion(actor, permit.scope(), operation, key, row.campaignId(), row.version());
			return view(mapper.find(actor.tenantId(), id, version));
		});
	}

	/** 路由锁先于实际版本组锁和回执；等待业务锁后再次限制许可期限。 */
	private void lockPermit(Actor actor, EmployeeAccess.ResourcePermit permit) {
		access.lock(permit.scope());
		var row = mapper.lockGroup(actor.tenantId(), permit.fact().id()).stream()
			.filter(r -> r.version() == permit.fact().version()).findFirst()
			.orElseThrow(() -> new DomainException(DomainException.Code.NOT_FOUND, "活动版本不存在"));
		// 内容不可变；状态lockVersion递增不应使已成功的原键重试变成状态冲突。
		if (!permit.fact().equals(fact(row)))
			throw new DomainException(DomainException.Code.CONFLICT, "活动内容版本事实变化");
		access.lock(permit.scope());
	}

	private static EmployeeAccess.ResourceFact fact(CampaignMapper.Row row) {
		return new EmployeeAccess.ResourceFact(CAMPAIGN_READ.resourceType(), row.campaignId(), row.version());
	}

	/** 保留旧命令的输入形状，中央身份变更不能回放旧主体的成功结果。 */
	private static Object command(EmployeeAccess.ScopePermit permit, String id, long version, long expected) {
		return permit.identity() == null ? List.of(id, version, expected) : List.of(id, version, expected, permit.identity());
	}

	/** 人群资格只影响对应活动，MISS/UNKNOWN不会被活动内部NOT反转。 */
	public Candidates candidates(Actor actor, String storeId, String memberId, java.time.Instant now) {
		return candidates(actor.tenantId(), memberId, mapper.published(actor.tenantId(), storeId, now), now);
	}

	private Candidates candidates(String tenant, String memberId, List<CampaignMapper.Row> rows,
			java.time.Instant now) {
		if (rows.size() > 100)
			throw new DomainException(DomainException.Code.LIMIT_EXCEEDED, "活动候选超过上限");
		var refs = rows.stream()
			.filter(r -> r.policyJson() != null)
			.map(r -> JsonCodec.read(r.policyJson(), Policy.class).audience())
			.filter(java.util.Objects::nonNull)
			.distinct()
			.toList();
		var sources = assets.sources(tenant, memberId, refs, now);
		var indexed = new java.util.HashMap<MarketingAssets.Ref, MarketingAssets.Source>();
		for (var source : sources)
			indexed.put(new MarketingAssets.Ref(source.audienceId(), source.version()), source);
		var offers = rows.stream().map(r -> {
			com.lrj.commerce.marketing.api.Condition condition = JsonCodec.read(r.ruleJson(), RuleNode.class)
				.toCondition();
			var policy = r.policyJson() == null ? null : JsonCodec.read(r.policyJson(), Policy.class);
			if (policy != null && policy.audience() != null) {
				String match = indexed.get(policy.audience()).match();
				if (!match.equals("HIT"))
					condition = new com.lrj.commerce.marketing.api.Condition.Literal(
							match.equals("MISS") ? com.lrj.commerce.marketing.api.Condition.Truth.NO_MATCH
									: com.lrj.commerce.marketing.api.Condition.Truth.UNKNOWN);
			}
			return new Offer(new Scope(tenant, r.merchantId(), r.storeId()), r.campaignId(), r.version(), r.validFrom(),
					r.validTo(), money(r.minimumSpend()), money(r.discountAmount()), condition,
					policy == null || policy.terms() == null ? 0 : policy.terms().percentageBps(),
					policy == null || policy.terms() == null ? null : pricing(policy.terms().pricing()));
		}).toList();
		return new Candidates(offers, sources);
	}

	/** 预览真实会员和目录价格，但不存报价、不预占库存/预算、不发权益。 */
	public PreviewResult preview(Actor actor, String id, long version, Preview input) {
		var scope = access.scope(actor, CAMPAIGN_PREVIEW);
		Identifiers.require(id);
		Inputs.require(version > 0 && input != null && input.items() != null && !input.items().isEmpty()
				&& input.items().size() <= 100, "预览购物清单无效");
		var row = Inputs.found(mapper.find(actor.tenantId(), id, version));
		var permit = access.resource(actor, scope, fact(row));
		members.requireActive(actor, input.memberId());
		stores.requireActive(actor, row.storeId());
		var quantities = new java.util.TreeMap<String, Integer>();
		for (var item : input.items()) {
			Inputs.require(item != null && item.quantity() > 0 && item.quantity() <= 10000, "购买数量无效");
			Identifiers.require(item.skuId());
			int count = quantities.getOrDefault(item.skuId(), 0) + item.quantity();
			Inputs.require(count <= 10000, "合并购买数量超限");
			quantities.put(item.skuId(), count);
		}
		var skus = catalog.published(actor, row.storeId(), new java.util.ArrayList<>(quantities.keySet()));
		var lines = skus.stream()
			.map(sku -> new Line(sku.skuId(), sku.skuId(), money(sku.unitPrice()), quantities.get(sku.skuId())))
			.toList();
		var at = input.at() == null ? clock.instant() : input.at();
		boolean comparePublished = Boolean.TRUE.equals(input.includePublishedCompetition());
		var competition = comparePublished
				? new java.util.ArrayList<>(mapper.published(actor.tenantId(), row.storeId(), at))
				: new java.util.ArrayList<CampaignMapper.Row>();
		// 预览的草稿将替换本活动的已发布版本，和未来发布时的单活跃版本语义相同。
		competition.removeIf(candidate -> candidate.campaignId().equals(row.campaignId()));
		competition.add(row);
		var candidates = candidates(actor.tenantId(), input.memberId(), competition, at);
		Money gross = lines.stream()
			.map(line -> line.unitPrice().multiply(line.quantity()))
			.reduce(Money.ZERO, Money::add);
		var result = decisions.decide(new Request(new Scope(actor.tenantId(), row.merchantId(), row.storeId()),
				input.memberId(), at, lines, MemberRuleFacts
					.from(memberGrowth.facts(actor.tenantId(), input.memberId()), gross.amount().toPlainString()),
				candidates.offers()));
		var preview = new PreviewResult(result.gross().amount().toPlainString(), result.discount().amount().toPlainString(),
				result.payable().amount().toPlainString(),
				result.lines()
					.stream()
					.map(line -> new PreviewLine(line.skuId(), line.gross().amount().toPlainString(),
							line.discount().amount().toPlainString(), line.payable().amount().toPlainString()))
					.toList(),
				result.trace(), candidates.sources(),
				comparePublished
						? "模拟此版本与当前已发布活动竞争；不含优惠券，不预占库存或预算，实际下单再次校验。模拟时间不回溯会员事实。"
						: "仅模拟该活动版本；不含优惠券，不预占库存或预算，实际下单再次校验。模拟时间不回溯会员事实。",
				result.selected());
		// 预览没有副作用，但长链路返回前仍复核原身份/范围和实际版本。
		var refreshed = access.scope(actor, CAMPAIGN_PREVIEW);
		EmployeeAccess.requireSame(permit.scope(), refreshed);
		access.resource(actor, refreshed, fact(Inputs.found(mapper.find(actor.tenantId(), id, version))));
		return preview;
	}

	private com.lrj.commerce.marketing.api.DecisionModels.Pricing pricing(CampaignApi.Pricing input) {
		if (input == null)
			return null;
		Inputs.require(input.tiers() != null && input.tiers().size() <= 8, "阶梯数量无效");
		return new com.lrj.commerce.marketing.api.DecisionModels.Pricing(input.includedSkuIds(), input.excludedSkuIds(),
				input.tiers().stream().map(tier -> {
					Inputs.require(tier != null, "阶梯不能为空");
					return new com.lrj.commerce.marketing.api.DecisionModels.Tier(money(tier.minimumSpend()),
							money(tier.discountAmount()), tier.percentageBps());
				}).toList());
	}

}
