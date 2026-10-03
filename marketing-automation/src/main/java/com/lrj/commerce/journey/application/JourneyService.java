package com.lrj.commerce.journey.application;

import com.lrj.commerce.runtime.api.validation.ListFilter;
import com.lrj.commerce.journey.api.JourneyApi;
import com.lrj.commerce.journey.infrastructure.persistence.JourneyMapper;
import com.lrj.commerce.kernel.*;
import com.lrj.commerce.member.profile.api.MemberApi;
import com.lrj.commerce.store.management.api.StoreApi;
import com.lrj.commerce.benefit.entitlement.api.EntitlementApi;
import com.lrj.commerce.aftersales.api.AftersaleApi;
import com.lrj.commerce.ordering.order.api.OrderApi;
import com.lrj.commerce.marketing.api.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import com.lrj.commerce.runtime.api.event.EventHandler;
import com.lrj.commerce.runtime.api.identity.Actor;
import static com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.*;
import com.lrj.commerce.runtime.api.validation.Inputs;
import com.lrj.commerce.runtime.command.Commands;
import com.lrj.commerce.runtime.recovery.RecoveryAudit;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import com.lrj.commerce.runtime.work.FailureClass;
import com.lrj.commerce.runtime.work.RetryPolicy;
import com.lrj.commerce.runtime.work.TenantRotation;
import com.lrj.commerce.runtime.work.WorkLanes;

/** 单节点效果与检查点同事务，等待靠数据库时间点恢复，不占用睡眠线程。 */
@Service
public class JourneyService implements JourneyApi, EventHandler {

	private final com.lrj.commerce.member.behavior.api.MemberBehaviorApi behavior;

	private final com.lrj.commerce.benefit.coupon.api.CouponApi coupons;

	private final JourneyMapper mapper;
	private final JourneyAuthorization authorization;
	private final TransactionTemplate readTx;

	private final JourneyActions actions;

	private final com.lrj.commerce.campaign.asset.api.MarketingAssets assets;

	private final Commands commands;

	private final MemberApi members;

	private final com.lrj.commerce.member.growth.api.MemberGrowthApi memberGrowth;

	private final StoreApi stores;

	private final EntitlementApi benefits;

	private final OrderApi orders;

	private final AftersaleApi aftersales;

	private final RuleDecisionPort rules;

	private final Clock clock;

	private final TransactionTemplate tx;

	private final TenantRotation journeys;

	/** 旅程车道：每次访问一个租户最多推进20步生命周期扫描和5个实例节点（逐项事务），每轮最多200项或500毫秒，租户轮转。 */
	public static final TenantRotation.Policy JOURNEYS = new TenantRotation.Policy(25, 50,
			java.time.Duration.ofMillis(500), 200);

	public JourneyService(JourneyMapper mapper, Commands commands, MemberApi members, StoreApi stores,
			EntitlementApi benefits, OrderApi orders, AftersaleApi aftersales, RuleDecisionPort rules, Clock clock,
			PlatformTransactionManager manager, com.lrj.commerce.member.growth.api.MemberGrowthApi memberGrowth,
			com.lrj.commerce.campaign.asset.api.MarketingAssets assets,
			com.lrj.commerce.member.behavior.api.MemberBehaviorApi behavior,
			com.lrj.commerce.benefit.coupon.api.CouponApi coupons, WorkLanes lanes, JourneyAuthorization authorization) {
		this.authorization=authorization;
		readTx=new TransactionTemplate(manager);readTx.setReadOnly(true);readTx.setTimeout(10);readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
		this.behavior = behavior;
		this.coupons = coupons;
		this.assets = assets;
		this.memberGrowth = memberGrowth;
		this.mapper = mapper;
		this.actions = new JourneyActions(mapper, benefits, coupons);
		this.commands = commands;
		this.members = members;
		this.stores = stores;
		this.benefits = benefits;
		this.orders = orders;
		this.aftersales = aftersales;
		this.rules = rules;
		this.clock = clock;
		tx = new TransactionTemplate(manager);
		tx.setTimeout(10);
		tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		journeys = lanes.rotation("journeys", JOURNEYS, () -> mapper.backlog(now()));
	}

	/** 图必须有界、无环、可达且所有分支收敛到终止节点。 */
	public View create(Actor actor, String key, Definition input) {
		var permit=authorization.scope(actor,JOURNEY_CREATE);
		validate(input);
		return commands.runGuarded(actor, "journey.create", key, authorization.input(permit,input), View.class, ()->authorization.lock(permit), () -> {
			stores.requireActive(actor, input.storeId());
			validateBindings(actor, input);
			if (mapper.find(actor.tenantId(), input.journeyId(), input.version()) != null)
				throw conflict("旅程内容版本已存在，修改必须创建新版本");
			mapper.definition(actor.tenantId(), input, JsonCodec.write(input));
			authorization.audit(actor,permit,"journey.create",key,input.journeyId(),input.version());
			authorization.lock(permit);
			return view(mapper.find(actor.tenantId(), input.journeyId(), input.version()));
		});
	}

	/** 固定权益引用在创建与发布时都核对，审批不授权过期或跨店铺绑定。 */
	private void validateBindings(Actor actor, Definition definition) {
		for (var node : definition.nodes())
			if (node.kind() == Kind.GRANT)
				benefits.validateBinding(actor.tenantId(), definition.storeId(), node.benefit(), definition.validFrom(),
						definition.validTo().plusSeconds(definition.maxDurationSeconds()));
		for (var node : definition.nodes())
			if (node.kind() == Kind.COUPON)
				coupons.validateExchange(actor.tenantId(), definition.storeId(), node.coupon().definitionId(),
						node.coupon().version(), definition.validFrom(),
						definition.validTo().plusSeconds(definition.maxDurationSeconds()));
	}

	private void validate(Definition definition) {
		com.lrj.commerce.journey.domain.JourneyGraph.requireValid(definition);
	}

	/** 不产生草稿、命令或权益效果；无效结构以稳定配置码返回。 */
	@Override
	public Validation validate(Actor actor, Definition definition) {
		var permit=authorization.scope(actor,JOURNEY_VALIDATE);
		var result = com.lrj.commerce.journey.domain.JourneyGraph.validate(definition);
		if (result.valid()) {
			stores.requireActive(actor, definition.storeId());
			validateBindings(actor, definition);
		}
		authorization.after(actor,permit);
		return result;
	}

	/** 与运行时共用图、可信事实和规则判定；WAIT后的可变事实不能在预览时预测。 */
	@Override
	public PreviewResult preview(Actor actor, String id, long version, Preview input) {
		Identifiers.require(id);
		Inputs.require(version > 0 && input != null, "预览参数无效");
		Inputs.found(mapper.find(actor.tenantId(),id,version));
		var permit=authorization.object(actor,JOURNEY_PREVIEW,id,version).scope();
		Identifiers.require(input.memberId());
		var definition = view(Inputs.found(mapper.find(actor.tenantId(), id, version))).content();
		validate(definition);
		members.requireActive(actor, input.memberId());
		String amount = null;
		if (input.orderId() != null) {
			Identifiers.require(input.orderId());
			var order = orders.internalRead(actor.tenantId(), input.orderId());
			if (!order.memberId().equals(input.memberId()) || !order.storeId().equals(definition.storeId()))
				throw new DomainException(DomainException.Code.NOT_FOUND, "来源订单不存在");
			amount = order.payable();
		}
		var facts = com.lrj.commerce.campaign.rule.api.MemberRuleFacts.from(
				memberGrowth.facts(actor.tenantId(), input.memberId()), amount);
		var at = input.at() == null ? now() : input.at();
		var path = new ArrayList<PreviewStep>();
		String current = definition.entry();
		for (int ordinal = 0; ordinal < definition.nodes().size(); ordinal++) {
			String nodeId = current;
			var node = definition.nodes().stream().filter(n -> n.id().equals(nodeId)).findFirst().orElseThrow();
			String decision = null;
			String next = node.next();
			String stop = null;
			switch (node.kind()) {
				case WAIT -> stop = "FUTURE_DEPENDENT";
				case END -> stop = "COMPLETED";
				case DECIDE -> {
					var truth = rules.evaluate(node.rule().toCondition(), facts);
					decision = truth.name();
					next = com.lrj.commerce.journey.domain.JourneyGraph.branch(node, truth);
					if (truth == Condition.Truth.UNKNOWN)
						stop = "RULE_UNKNOWN";
				}
				case GRANT, COUPON, NOTIFY -> decision = "ACTION_NOT_EXECUTED";
			}
			path.add(new PreviewStep(node.id(), node.kind(), decision, next));
			if (stop != null) {
				authorization.after(actor,permit);
				return new PreviewResult(id, version, at, List.copyOf(path), stop);
			}
			current = next;
		}
		throw conflict("旅程预览超过图上限");
	}

	/** 查询每个旅程的最新内容版本，实例仍保留自己的旧版本。 */
	public List<View> definitions(Actor actor, String after, int limit) {
		return definitions(actor, after, limit, ListFilter.none());
	}

	/** 只读条件先筛选再分页，保持原用例的权限复核。 */
	public List<View> definitions(Actor actor, String after, int limit, ListFilter filter) {
		filter.requireNoEnabled();
		filter.requireNoTime();
		var permit=authorization.scope(actor,JOURNEY_READ);
		Inputs.page(after, limit);
		var result=mapper.definitions(actor.tenantId(), after, limit, filter).stream().map(this::view).toList();
		authorization.after(actor,permit);return result;
	}

	/** 审批状态和乐观版本共同约束发布，已暂停版本可重新启用。 */
	public View change(Actor actor, String key, String id, long version, long expected, String action) {
		Identifiers.require(id);
		Inputs.require(version > 0 && expected >= 0
				&& Set.of("submit", "approve", "reject", "publish", "pause").contains(action), "旅程审批参数无效");
		var capability=switch(action) {case "submit"->JOURNEY_SUBMIT;case "approve"->JOURNEY_APPROVE;case "reject"->JOURNEY_REJECT;case "publish"->JOURNEY_PUBLISH;default->JOURNEY_PAUSE;};
		Inputs.found(mapper.find(actor.tenantId(),id,version));
		var permit=authorization.object(actor,capability,id,version).scope();
		return commands.runGuarded(actor, "journey." + action, key, authorization.input(permit,List.of(id, version, expected)), View.class, ()->authorization.lock(permit), () -> {
			var row = mapper.group(actor.tenantId(), id)
				.stream()
				.filter(r -> r.version() == version)
				.findFirst()
				.orElseThrow(() -> new DomainException(DomainException.Code.NOT_FOUND, "旅程版本不存在"));
			boolean allowed = switch (action) {
				case "submit" -> row.status().equals("DRAFT");
				case "approve", "reject" -> row.status().equals("IN_REVIEW");
				case "publish" -> Set.of("APPROVED", "PAUSED").contains(row.status());
				case "pause" -> row.status().equals("PUBLISHED");
				default -> false;
			};
			if (!allowed || row.lockVersion() != expected)
				throw conflict("旅程状态或版本冲突");
			String next = switch (action) {
				case "submit" -> "IN_REVIEW";
				case "approve" -> "APPROVED";
				case "reject" -> "REJECTED";
				case "publish" -> "PUBLISHED";
				default -> "PAUSED";
			};
			if (action.equals("publish")) {
				var d = view(row).content();
				validate(d);
				validateBindings(actor, d);
				if (!now().isBefore(d.validTo()))
					throw conflict("旅程入组窗口已结束");
				stores.requireActive(actor, d.storeId());
				mapper.pauseOthers(actor.tenantId(), id);
			}
			if (mapper.change(actor.tenantId(), id, version, expected, next) != 1)
				throw conflict("旅程并发修改");
			if (action.equals("publish")) {
				var definition = view(row).content();
				authorization.publish(actor,permit,definition,key,now());
				if (lifecycle(definition.trigger()))
					mapper.ensureScan(actor.tenantId(), definition, now());
			}
			authorization.audit(actor,permit,"journey."+action,key,id,version);
			authorization.lock(permit);
			return view(mapper.find(actor.tenantId(), id, version));
		});
	}

	/** 相同触发键只产生一个实例，换会员重用该键属于冲突。 */
	public Instance enroll(Actor actor, String key, Start input) { return prepareEnroll(actor,key,input).execute(); }

	/** 先完成实时引用与原来源核验，供OpsPage在自己的原子事务中调用闭包。 */
	@Override public PreparedEnrollment prepareEnroll(Actor actor,String key,Start input) {
		var permit=authorization.scope(actor,JOURNEY_INSTANCE_CREATE);
		Inputs.require(input != null && input.version() > 0, "入组参数无效");
		Identifiers.require(input.journeyId());
		Identifiers.require(input.memberId());
		Identifiers.require(input.eventKey());
		var request=authorization.input(permit,input);
		var definition=Inputs.found(mapper.find(actor.tenantId(),input.journeyId(),input.version()));
		var source=authorization.manual(actor,permit,view(definition).content(),key,now());
		var receipt=commands.completedReceipt(actor,"journey.enroll",key,request,Instance.class);
		var existing=receipt==null?mapper.byEvent(actor.tenantId(),input.journeyId(),input.version(),input.eventKey()):receipt;
		var original=existing==null?null:authorization.gate(actor.tenantId(),authorization.source(actor.tenantId(),existing));
		return ()->commands.runGuarded(actor, "journey.enroll", key, request, Instance.class, ()->{authorization.lock(permit);if(original!=null)authorization.lock(original);}, result->{
			if(original==null)throw conflict("入组回执来源在并发期间变化，请以原键重试");
			authorization.requireFresh(original);
		}, () -> {
			var row = Inputs.found(mapper.find(actor.tenantId(), input.journeyId(), input.version()));
			var d = view(row).content();
			if (!row.status().equals("PUBLISHED") || d.trigger() != Trigger.MANUAL)
				throw conflict("旅程不允许手工入组");
			requireWindow(d);
			members.requireActive(actor, input.memberId());
			var result=start(actor.tenantId(), d, input.memberId(), null, input.eventKey(),false,source);
			authorization.audit(actor,permit,"journey.enroll",key,result.instanceId(),result.journeyVersion());
			authorization.lock(permit);
			return result;
		});
	}

	private void requireWindow(Definition d) {
		var now = now();
		if (now.isBefore(d.validFrom()) || !now.isBefore(d.validTo()))
			throw conflict("旅程不在入组窗口");
	}

	private Instance start(String tenant, Definition d, String member, String order, String event, boolean automatic) {
		var source=authorization.system(tenant,d,event,now());
		var gate=authorization.gate(tenant,source);authorization.lock(gate);
		var result=start(tenant,d,member,order,event,automatic,source);
		authorization.requireFresh(gate);return result;
	}
	private Instance start(String tenant, Definition d, String member, String order, String event, boolean automatic, JourneyAuthorization.Source source) {
		if (!behavior.journeyAllowed(tenant, member)) {
			if (automatic)
				return null;
			throw conflict("会员已关闭旅程或状态不可入组");
		}
		var existing = mapper.byEvent(tenant, d.journeyId(), d.version(), event);
		if (existing != null) {
			if (!existing.memberId().equals(member) || !Objects.equals(existing.orderId(), order))
				throw conflict("触发键已经绑定其他来源");
			return existing;
		}
		String effect = JsonCodec.hash("entry/" + d.journeyId() + "/" + d.version() + "/" + event);
		if (d.controls() != null) {
			mapper.ensureCap(tenant, d.journeyId(), member);
			var cap = mapper.lockCap(tenant, d.journeyId(), member);
			if (mapper.effectExists(tenant, effect))
				return null;
			long bucket = window(d.controls().entryWindowSeconds());
			int count = cap.entryWindow() == bucket ? cap.entries() : 0;
			if (count >= d.controls().maxEntries()) {
				if (!automatic)
					throw conflict("会员已达到当前旅程窗口入组上限");
				mapper.cap(tenant, d.journeyId(), member, false, bucket, count, true);
				mapper.effect(tenant, effect, d, member, "ENTRY_SUPPRESSED", now());
				return null;
			}
			mapper.cap(tenant, d.journeyId(), member, false, bucket, count + 1, false);
		}
		var now = now();
		mapper.instance(tenant, UUID.randomUUID().toString(), d, member, order, event, now,
				now.plusSeconds(d.maxDurationSeconds()));
		var inserted=mapper.byEvent(tenant,d.journeyId(),d.version(),event);
		if(mapper.sourceOnce(tenant,inserted.instanceId(),JsonCodec.write(source))!=1)throw conflict("旅程原来源不能覆盖");
		mapper.effect(tenant, effect, d, member, "ENROLLED", now);
		return inserted;
	}

	private long window(int seconds) {
		long at = now().getEpochSecond();
		return at / seconds * seconds;
	}

	/** 按认证身份限制实例列表，不开放任意memberId过滤。 */
	public List<Instance> instances(Actor actor, String after, int limit) {
		return instances(actor, after, limit, ListFilter.none());
	}

	/** 只读条件先筛选再分页，保持原用例的权限复核。 */
	public List<Instance> instances(Actor actor, String after, int limit, ListFilter filter) {
		filter.requireNoEnabled();
		filter.requireNoTime();
		Inputs.page(after, limit);
		if(actor.role()==Actor.Role.MEMBER)return mapper.instances(actor.tenantId(),members.current(actor).memberId(),after,limit, filter);
		var permit=authorization.scope(actor,JOURNEY_INSTANCE_READ);
		var result=mapper.instances(actor.tenantId(),null,after,limit, filter);
		authorization.after(actor,permit);return result;
	}

	/** 一个短只读快照同时读取检查点和历史，避免推进期间误报历史覆盖缺口。 */
	@Override
	public History history(Actor actor, String id, long afterVersion, int limit) {
		Identifiers.require(id);
		Inputs.require(afterVersion >= -1 && limit >= 1 && limit <= 50, "历史分页参数无效");
		var candidate=Inputs.found(mapper.findInstance(actor.tenantId(),id));
		var permit=actor.role()==Actor.Role.MEMBER?null:authorization.object(actor,JOURNEY_INSTANCE_READ,id,candidate.journeyVersion()).scope();
		var result=readTx.execute(status->{
		var row = Inputs.found(mapper.findInstance(actor.tenantId(), id));
		if (actor.role() == Actor.Role.MEMBER && !members.current(actor).memberId().equals(row.memberId()))
			throw new DomainException(DomainException.Code.NOT_FOUND, "旅程实例不存在");
		var definition = view(Inputs.found(mapper.find(actor.tenantId(), row.journeyId(), row.journeyVersion()))).content();
		String coverage = mapper.traceOrigin(actor.tenantId(), id) == null ? "LEGACY_PARTIAL"
				: mapper.recordedSteps(actor.tenantId(), id) == row.steps() ? "COMPLETE" : "PARTIAL";
		return new History(row, definition, mapper.triggerKey(actor.tenantId(), id), coverage,
				mapper.history(actor.tenantId(), id, afterVersion, limit));
		});
		if(permit!=null)authorization.after(actor,permit);
		return result;
	}

	/** 运维恢复审计：重试、取消等对停止工作的人工干预与状态变更同一事务记录。 */
	private com.lrj.commerce.runtime.recovery.RecoveryAudit audit;

	@org.springframework.beans.factory.annotation.Autowired
	void audit(com.lrj.commerce.runtime.recovery.RecoveryAudit audit) {
		this.audit = audit;
	}

	/** 取消不逆转已执行效果；隔离重试必须仍在截止时间内。 */
	public Instance control(Actor actor, String key, String id, String action) {
		Identifiers.require(id);
		Inputs.require(Set.of("cancel", "retry").contains(action), "实例动作无效");
		var candidate=Inputs.found(mapper.findInstance(actor.tenantId(),id));
		var permit=authorization.object(actor,JOURNEY_INSTANCE_CONTROL,id,candidate.journeyVersion()).scope();
		var source=action.equals("retry")?authorization.gate(actor.tenantId(),authorization.source(actor.tenantId(),candidate)):null;
		return commands.runGuarded(actor, "journey." + action, key, authorization.input(permit,id), Instance.class, ()->{authorization.lock(permit);if(source!=null)authorization.lock(source);}, () -> {
			var old = Inputs.found(mapper.lock(actor.tenantId(), id));
			if (action.equals("retry")) {
				if (old.status() != State.ISOLATED || !now().isBefore(old.deadline()))
					throw conflict("实例不允许重试");
			}
			else if (!active(old))
				throw conflict("实例已终止");
			if (mapper.control(actor.tenantId(), id, old.version(), action.equals("retry") ? "RUNNING" : "CANCELLED",
					now()) != 1)
				throw conflict("实例并发修改");
			audit.record(actor, "journey." + action, key, "journey.instance", id,
					action.equals("retry") ? "RETRY" : "CANCEL", old.status().name(),
					action.equals("retry") ? "RUNNING" : "CANCELLED", null, null,
					com.lrj.commerce.runtime.recovery.RecoveryAudit.APPLIED, null);
			authorization.audit(actor,permit,"journey."+action,key,id,old.journeyVersion());
			authorization.lock(permit);if(source!=null)authorization.requireFresh(source);
			return mapper.findInstance(actor.tenantId(), id);
		});
	}

	/** 站内信是持久化的业务触达，不调用外部消息系统。 */
	public List<Notification> notifications(Actor actor, String after, int limit) {
		Inputs.page(after, limit);
		return mapper.notifications(actor.tenantId(), members.current(actor).memberId(), after, limit);
	}

	public String consumer() {
		return "journey-order-paid-v1";
	}

	/** 重放分类见phase4重放安全矩阵。 */
	@Override
	public com.lrj.commerce.runtime.api.event.EventHandler.ReplaySafety replaySafety() {
		return com.lrj.commerce.runtime.api.event.EventHandler.ReplaySafety.notReplayable(
				"入组唯一键与发生时间窗口使重复执行无效；但迟到入组会继续发放券、权益与站内通知",
				com.lrj.commerce.runtime.api.event.EventHandler.SideEffect.IDEMPOTENT_WRITE,
				com.lrj.commerce.runtime.api.event.EventHandler.SideEffect.COMPENSATABLE_SIDE_EFFECT);
	}

	public Set<String> types() {
		return Set.of("order.paid.v1", "member.registered.v1", "member.level.changed.v1", "segment.member.entered.v1");
	}

	/** 事件触发只读当前可信事实，发布之前的事件不追溯执行。 */
	public void handle(Event event) {
		String member, orderId = null, store = null, segment = null;
		Trigger trigger;
		switch (event.eventType()) {
			case "order.paid.v1" -> {
				var order = orders.internalRead(event.tenantId(), event.aggregateId());
				if (aftersales.fullyReturned(event.tenantId(), order.orderId()))
					return;
				if (!Set.of("PAID", "FULFILLING", "COMPLETED").contains(order.status()))
					throw conflict("支付触发订单状态无效");
				member = order.memberId();
				orderId = order.orderId();
				store = order.storeId();
				trigger = Trigger.ORDER_PAID;
			}
			case "member.registered.v1" -> {
				member = JsonCodec
					.read(event.payloadJson(), com.lrj.commerce.member.growth.api.MemberGrowthApi.Registered.class)
					.memberId();
				trigger = Trigger.MEMBER_REGISTERED;
			}
			case "member.level.changed.v1" -> {
				member = JsonCodec
					.read(event.payloadJson(), com.lrj.commerce.member.growth.api.MemberGrowthApi.LevelChanged.class)
					.memberId();
				trigger = Trigger.LEVEL_CHANGED;
			}
			case "segment.member.entered.v1" -> {
				var entry = JsonCodec.read(event.payloadJson(),
						com.lrj.commerce.campaign.segment.api.SegmentApi.Entered.class);
				member = entry.memberId();
				segment = entry.segmentId();
				trigger = Trigger.SEGMENT_ENTERED;
				var source = assets.sources(event.tenantId(), member,
						List.of(new com.lrj.commerce.campaign.asset.api.MarketingAssets.Ref(entry.audienceId(),
								entry.snapshotVersion())),
						now())
					.getFirst();
				if (!source.match().equals("HIT"))
					return;
			}
			default -> throw conflict("未知旅程事件");
		}
		var facts = memberGrowth.facts(event.tenantId(), member);
		if (!facts.status().equals("ACTIVE"))
			return;
		var rows = mapper.triggered(event.tenantId(), trigger.name(), store, now(), event.createdAt());
		Inputs.require(rows.size() <= 10, "匹配的自动旅程超过10个");
		for (var row : rows) {
			var d = view(row).content();
			if (trigger == Trigger.SEGMENT_ENTERED && !Objects.equals(segment, d.controls().segmentId()))
				continue;
			if (d.controls() != null && d.controls().entryRule() != null && rules.evaluate(
					d.controls().entryRule().toCondition(),
					com.lrj.commerce.campaign.rule.api.MemberRuleFacts.from(facts, orderId == null ? null
							: orders.internalRead(event.tenantId(), orderId).payable())) != Condition.Truth.MATCH)
				continue;
			start(event.tenantId(), d, member, orderId, trigger == Trigger.ORDER_PAID ? orderId : event.eventId(),
					true);
		}
	}

	/** 全额退款消费者先取得实例锁，与执行节点的实例到权益锁顺序一致。 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void cancelForOrder(String tenant, String order) {
		var rows = mapper.orderInstances(tenant, order);
		Inputs.require(rows.size() <= 10, "订单旅程数量异常");
		for (var row : rows)
			if (active(row) && mapper.control(tenant, row.instanceId(), row.version(), "CANCELLED", now()) != 1)
				throw conflict("旅程取消并发冲突");
	}

	private boolean active(Instance i) {
		return Set.of(State.RUNNING, State.WAITING, State.ISOLATED).contains(i.status());
	}

	/** 租户公平轮转，每租户每次最多20步扫描与5个实例，避免大租户独占。 */
	public int tick() {
		return journeys.run((after, limit) -> mapper.tenants(after, now(), limit), this::pumpTenant);
	}

	/** 管理台只能触发当前租户的有限批次。 */
	public int pump(Actor actor) {
		var permit=authorization.scope(actor,JOURNEY_PUMP);
		var result=pumpTenant(actor.tenantId(),journeys.manual());
		authorization.after(actor,permit);return result;
	}

	/** 瞬时失败（依赖不可用、锁冲突、超时）只延后不计次数，实例截止时间终止重试；其他失败计次，5次隔离。 */
	private int pumpTenant(String tenant, TenantRotation.Run run) {
		int count = scanTenant(tenant, run);
		for (var candidate : mapper.due(tenant, now())) {
			if (run.exhausted())
				break;
			run.attempted();
			// 领取前的候选可能已被另一执行器推进，失败计数必须绑定真正执行的节点版本。
			var attempted = new java.util.concurrent.atomic.AtomicReference<>(candidate);
			try {
				var source=authorization.source(tenant,candidate);
				var gate=authorization.gate(tenant,source);
				if (Boolean.TRUE.equals(tx.execute(s -> {
					authorization.lock(gate);
					behavior.journeyAllowed(tenant, candidate.memberId());
					var row = mapper.dueLock(tenant, candidate.instanceId(), now());
					if (row == null)
						return false;
					attempted.set(row);
					if(!Objects.equals(source,authorization.source(tenant,row)))throw conflict("旅程原来源已变化");
					execute(tenant, row);
					authorization.requireFresh(gate);
					return true;
				})))
					count++;
				run.succeeded();
			}
			catch (RuntimeException failure) {
				// 节点事务已回滚，独立事务仅记录有界重试，不保留可能包含敏感数据的异常文本。
				var type = FailureClass.of(failure);
				boolean counted = !type.transientFailure();
				var retryAt = now().plusMillis(counted
						? (1L << Math.min(attempted.get().attempts() + 1, 5)) * 1000
								+ java.util.concurrent.ThreadLocalRandom.current().nextInt(1000)
						: RetryPolicy.deferralMillis(java.util.concurrent.ThreadLocalRandom.current().nextDouble()));
				tx.executeWithoutResult(s -> {
					var current = mapper.lock(tenant, candidate.instanceId());
					if (current != null && current.version() == attempted.get().version()
							&& mapper.failed(tenant, current.instanceId(), current.version(), counted, retryAt) == 1) {
						String state = !counted ? "DEFERRED" : current.attempts() + 1 >= 5 ? "ISOLATED" : "FAILED";
						mapper.stepFailure(tenant, current, state, retryAt, type.name(), now());
					}
				});
				org.slf4j.LoggerFactory.getLogger(getClass())
					.warn("journey retry id={} failureClass={} errorType={}", candidate.instanceId(), type,
							failure.getClass().getSimpleName());
				if (run.failed(type))
					break;
			}
		}
		return count;
	}

	private void execute(String tenant, Instance row) {
		var now = now();
		mapper.stepStart(tenant, row, now);
		if (!now.isBefore(row.deadline())) {
			advance(tenant, row, row.currentNode(), State.TIMED_OUT, now, "DEADLINE");
			return;
		}
		if (!behavior.journeyAllowed(tenant, row.memberId())) {
			advance(tenant, row, row.currentNode(), State.CANCELLED, now, "MEMBER_DISABLED");
			return;
		}
		if (row.orderId() != null && aftersales.fullyReturned(tenant, row.orderId())) {
			advance(tenant, row, row.currentNode(), State.CANCELLED, now, "ORDER_REFUNDED");
			return;
		}
		var d = view(Inputs.found(mapper.find(tenant, row.journeyId(), row.journeyVersion()))).content();
		if (d.trigger() == Trigger.CART_ABANDONED) {
			var anchor = mapper.anchor(tenant, row.instanceId());
			if (anchor != null && orders.hasPaidSince(tenant, row.memberId(), d.storeId(), anchor)) {
				advance(tenant, row, row.currentNode(), State.CANCELLED, now, "CART_PURCHASED");
				return;
			}
		}
		var node = d.nodes().stream().filter(n -> n.id().equals(row.currentNode())).findFirst().orElseThrow();
		Inputs.require(row.steps() < 32, "旅程步数超过上限");
		var member = memberGrowth.facts(tenant, row.memberId());
		if (!member.status().equals("ACTIVE")) {
			advance(tenant, row, row.currentNode(), State.CANCELLED, now, "MEMBER_INACTIVE");
			return;
		}
		String next = node.next();
		State state = State.RUNNING;
		Instant due = now;
		String result = null;
		String decision = null;
		String outcome = null;
		String actionRef = null;
		switch (node.kind()) {
			case END -> {
				next = node.id();
				state = State.COMPLETED;
				result = "FINISHED";
				outcome = "FINISHED";
				mapper.effect(tenant, JsonCodec.hash("complete/" + row.instanceId()), d, row.memberId(), "COMPLETED",
						now);
			}
			case WAIT -> {
				state = State.WAITING;
				due = now.plusSeconds(node.seconds());
				outcome = "WAIT_SCHEDULED";
			}
			case DECIDE -> {
				Map<String, Fact> facts = com.lrj.commerce.campaign.rule.api.MemberRuleFacts.from(
						member,
						row.orderId() == null ? null : orders.internalRead(tenant, row.orderId()).payable());
				var truth = rules.evaluate(node.rule().toCondition(), facts);
				decision = truth.name();
				outcome = "DECIDED";
				if (truth == Condition.Truth.UNKNOWN) {
					next = node.id();
					state = State.COMPLETED;
					result = "RULE_UNKNOWN";
					outcome = "RULE_UNKNOWN";
				}
				else
					next = com.lrj.commerce.journey.domain.JourneyGraph.branch(node, truth);
			}
			case GRANT, COUPON, NOTIFY -> {
				var action = actions.execute(tenant, row, d, node, now);
				outcome = action.outcome();
				actionRef = action.actionRef();
			}
		}
		advance(tenant, row, next, state, due, result, node.kind(), decision, outcome, actionRef);
	}

	private Instant now() {
		return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
	}

	private boolean lifecycle(Trigger trigger) {
		return Set.of(Trigger.BIRTHDAY, Trigger.DORMANT, Trigger.REPURCHASE, Trigger.CART_ABANDONED).contains(trigger);
	}

	/** 扫描与单会员入组共享事务；每个租户最多20步，游标提交后才能继续。 */
	private int scanTenant(String tenant, TenantRotation.Run run) {
		int count = 0;
		for (var candidate : mapper.dueScans(tenant, now())) {
			for (int i = 0; i < 4 && !run.exhausted(); i++) {
				var attempted = new java.util.concurrent.atomic.AtomicReference<>(candidate);
				run.attempted();
				try {
					var definition=view(Inputs.found(mapper.find(tenant,candidate.journeyId(),candidate.journeyVersion()))).content();
					var source=authorization.system(tenant,definition,"scan",now());
					var gate=authorization.gate(tenant,source);
					if (!Boolean.TRUE.equals(tx.execute(status -> {
						authorization.lock(gate);
						var scan = mapper.scanLock(tenant, candidate.journeyId(), candidate.journeyVersion());
						if (scan == null || scan.status().equals("ISOLATED") || scan.nextDue().isAfter(now()))
							return false;
						attempted.set(scan);
						var result=scanStep(tenant,scan);
						authorization.requireFresh(gate);return result;
					})))
						break;
					count++;
					run.succeeded();
				}
				catch (RuntimeException failure) {
					var old = attempted.get();
					var type = FailureClass.of(failure);
					boolean counted = !type.transientFailure();
					tx.executeWithoutResult(status -> mapper.scanFailed(tenant, old, counted,
							now().plusMillis(counted
									? (1L << Math.min(old.attempts() + 1, 5)) * 1000
											+ java.util.concurrent.ThreadLocalRandom.current().nextInt(1000)
									: RetryPolicy.deferralMillis(
											java.util.concurrent.ThreadLocalRandom.current().nextDouble()))));
					org.slf4j.LoggerFactory.getLogger(getClass())
						.warn("journey scan retry journey={} failureClass={} errorType={}", old.journeyId(), type,
								failure.getClass().getSimpleName());
					if (run.failed(type))
						return count;
					break;
				}
			}
		}
		return count;
	}

	private boolean scanStep(String tenant, Scan scan) {
		var now = now();
		var row = mapper.find(tenant, scan.journeyId(), scan.journeyVersion());
		var d = view(row).content();
		if (!row.status().equals("PUBLISHED") || now.isBefore(d.validFrom()) || !now.isBefore(d.validTo()))
			return false;
		boolean beginning = scan.status().equals("IDLE");
		Instant before = beginning ? now : scan.createdBefore();
		String cursor = beginning ? "" : scan.memberCursor();
		var batch = memberGrowth.scan(tenant, cursor, 1, before);
		if (batch.isEmpty()) {
			if (mapper.scanAdvance(tenant, scan, "IDLE", "", before,
					now.plusSeconds(d.lifecycle().scanIntervalSeconds()), 0, 0) != 1)
				throw conflict("扫描检查点已变化");
			return false;
		}
		var fact = batch.getFirst();
		int enrolled = 0;
		if (behavior.journeyAllowed(tenant, fact.memberId())) {
			// READ_COMMITTED事务在会员锁后重新读取事实，不使用扫描候选中的旧资格。
			fact = memberGrowth.facts(tenant, fact.memberId());
			var b = fact.behavior();
			Instant cart = d.trigger() == Trigger.CART_ABANDONED
					? behavior.latestCart(tenant, fact.memberId(), d.storeId()) : null;
			boolean matched = switch (d.trigger()) {
				case BIRTHDAY -> b.birthdayToday();
				case DORMANT -> (b.daysSinceOrder() == null ? b.daysSinceJoin() : b.daysSinceOrder()) >= d.lifecycle()
					.thresholdDays();
				case REPURCHASE -> b.daysSinceOrder() != null && b.daysSinceOrder() >= d.lifecycle().thresholdDays()
						&& new java.math.BigDecimal(fact.netSpend()).signum() > 0;
				case CART_ABANDONED -> cart != null && !cart.plusSeconds(d.lifecycle().cartDelaySeconds()).isAfter(now)
						&& !orders.hasPaidSince(tenant, fact.memberId(), d.storeId(), cart);
				default -> false;
			};
			if (matched && d.controls().entryRule() != null)
				matched = rules.evaluate(d.controls().entryRule().toCondition(),
						com.lrj.commerce.campaign.rule.api.MemberRuleFacts.from(fact, null)) == Condition.Truth.MATCH;
			if (matched) {
				String anchor = switch (d.trigger()) {
					case BIRTHDAY -> String.valueOf(now.atZone(ZoneOffset.UTC).getYear());
					case CART_ABANDONED -> cart.toString();
					default -> String.valueOf(b.lastOrderAt()) + "/" + window(d.controls().entryWindowSeconds());
				};
				String event = JsonCodec.hash(d.trigger() + "/" + fact.memberId() + "/" + anchor);
				if (mapper.byEvent(tenant, d.journeyId(), d.version(), event) == null) {
					var instance = start(tenant, d, fact.memberId(), null, event, true);
					if (instance != null) {
						enrolled = 1;
						if (cart != null)
							mapper.setAnchor(tenant, instance.instanceId(), cart);
					}
				}
			}
		}
		if (mapper.scanAdvance(tenant, scan, "RUNNING", fact.memberId(), before, now, 1, enrolled) != 1)
			throw conflict("扫描检查点已变化");
		return true;
	}

	/** 管理员可看到隔离原因和扫描边界，状态读取不会推进任务。 */
	public List<Scan> scans(Actor actor, String after, int limit) {
		return scans(actor, after, limit, ListFilter.none());
	}

	/** 只读条件先筛选再分页，保持原用例的权限复核。 */
	public List<Scan> scans(Actor actor, String after, int limit, ListFilter filter) {
		filter.requireNoEnabled();
		filter.requireNoTime();
		var permit=authorization.scope(actor,JOURNEY_SCAN_READ);
		Inputs.page(after, limit);
		var result=mapper.scans(actor.tenantId(),after,limit, filter);authorization.after(actor,permit);return result;
	}

	/** 只重试明确隔离版本，保留原游标且不撤销已提交的入组。 */
	public Scan retryScan(Actor actor, String key, String id, long version, ScanRetry input) {
		Identifiers.require(id);
		Inputs.require(version > 0 && input != null && input.expectedVersion() >= 0, "扫描恢复参数无效");
		Inputs.text(input.reason(), 256);
		Inputs.found(mapper.findScan(actor.tenantId(),id,version));
		var permit=authorization.object(actor,JOURNEY_SCAN_RETRY,id,version).scope();
		var fixed=view(Inputs.found(mapper.find(actor.tenantId(),id,version))).content();
		var gate=authorization.gate(actor.tenantId(),authorization.system(actor.tenantId(),fixed,"scan",now()));
		return commands.runGuarded(actor, "journey.scan.retry", key, authorization.input(permit,new Object[] { id, version, input }), Scan.class, ()->{authorization.lock(permit);authorization.lock(gate);}, () -> {
			var scan = Inputs.found(mapper.scanLock(actor.tenantId(), id, version));
			if (!scan.status().equals("ISOLATED") || scan.version() != input.expectedVersion())
				throw conflict("扫描状态或版本已变化");
			var definition = Inputs.found(mapper.find(actor.tenantId(), id, version));
			if (!definition.status().equals("PUBLISHED"))
				throw conflict("请先发布旅程");
			requireWindow(view(definition).content());
			if (mapper.scanRetry(actor.tenantId(), scan, now()) != 1)
				throw conflict("扫描并发修改");
			var after = mapper.scanLock(actor.tenantId(), id, version);
			audit.record(actor, "journey.scan.retry", key, "journey.scan", id + "/" + version, "RETRY", scan.status(),
					after.status(), null, input.reason(), com.lrj.commerce.runtime.recovery.RecoveryAudit.APPLIED,
					null);
			authorization.audit(actor,permit,"journey.scan.retry",key,id,version);
			authorization.lock(permit);authorization.requireFresh(gate);
			return after;
		});
	}

	/** 执行计数与商业收入分析分开，避免将触达次数伪装为营销增量效果。 */
	public List<EffectSummary> effects(Actor actor, String store, Instant from, Instant to, String after, int limit) {
		var permit=authorization.scope(actor,MARKETING_EFFECT_READ);
		stores.requireActive(actor, store);
		Inputs.page(after, limit);
		Inputs.require(from != null && to != null && to.isAfter(from)
				&& Duration.between(from, to).compareTo(Duration.ofDays(93)) <= 0, "分析窗口需为93天内");
		var result=mapper.effects(actor.tenantId(),store,from,to,after,limit);authorization.after(actor,permit);return result;
	}

	private void advance(String tenant, Instance old, String node, State status, Instant due, String result) {
		advance(tenant, old, node, status, due, result, null, null, result, null);
	}

	/** 动作、完成记录与版本推进共用事务，任一条件更新失败都回滚业务效果。 */
	private void advance(String tenant, Instance old, String node, State status, Instant due, String result,
			Kind kind, String decision, String outcome, String actionRef) {
		String stepState = status == State.WAITING ? "WAITING"
				: status == State.CANCELLED || status == State.TIMED_OUT ? "STOPPED" : "COMPLETED";
		if (mapper.stepFinish(tenant, old, kind, stepState, node, due, decision, outcome, actionRef, now()) != 1
				|| mapper.advance(tenant, old, node, status.name(), due, result) != 1)
			throw conflict("旅程检查点并发冲突");
	}

	private View view(JourneyMapper.Row row) {
		return new View(JsonCodec.read(row.definitionJson(), Definition.class), row.status(), row.lockVersion());
	}

	private DomainException conflict(String message) {
		return new DomainException(DomainException.Code.CONFLICT, message);
	}

}
