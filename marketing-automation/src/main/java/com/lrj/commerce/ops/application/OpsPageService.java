package com.lrj.commerce.ops.application;

import com.lrj.commerce.ops.api.OpsPageApi;
import com.lrj.commerce.ops.infrastructure.persistence.OpsPageMapper;
import com.lrj.commerce.kernel.*;
import com.lrj.commerce.store.management.api.StoreApi;
import com.lrj.commerce.journey.api.JourneyApi;
import org.springframework.stereotype.Service;
import java.util.*;
import com.lrj.commerce.benefit.coupon.api.CouponApi;
import com.lrj.commerce.benefit.entitlement.api.EntitlementApi;
import com.lrj.commerce.campaign.funding.api.CampaignFundingApi;
import com.lrj.commerce.campaign.management.api.CampaignApi;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import static com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.*;
import com.lrj.commerce.runtime.api.validation.Inputs;
import com.lrj.commerce.runtime.command.Commands;
import com.lrj.commerce.runtime.serialization.JsonCodec;

/** 低代码页面组合已有用例，发布权限和幂等仍在服务端执行。 */
@Service
public class OpsPageService implements OpsPageApi {

	/** 每一页及内嵌目标都使用自己的员工门禁，不能把页面能力扩展为业务写权限。 */
	@org.springframework.beans.factory.annotation.Autowired
	private EmployeeAccess access;

	private final OpsPageMapper mapper;

	private final Commands commands;

	private final StoreApi stores;

	private final CampaignApi campaigns;

	private final CouponApi coupons;

	private final JourneyApi journeys;

	private final EntitlementApi benefits;

	private final CampaignFundingApi budgets;

	public OpsPageService(OpsPageMapper mapper, Commands commands, StoreApi stores, CampaignApi campaigns,
			CouponApi coupons, JourneyApi journeys, EntitlementApi benefits, CampaignFundingApi budgets) {
		this.mapper = mapper;
		this.commands = commands;
		this.stores = stores;
		this.campaigns = campaigns;
		this.coupons = coupons;
		this.journeys = journeys;
		this.benefits = benefits;
		this.budgets = budgets;
	}

	private void validate(Definition input) {
		Inputs.require(input != null && input.version() > 0, "页面版本无效");
		Identifiers.require(input.pageId());
		Identifiers.require(input.storeId());
		Inputs.text(input.title(), 128);
		Inputs.require(input.sections() != null && !input.sections().isEmpty() && input.sections().size() <= 8
				&& input.actions() != null && input.actions().size() <= 4, "页面组件数量无效");
		var ids = new HashSet<String>();
		for (var s : input.sections()) {
			Inputs.require(s != null && s.source() != null, "数据源缺失");
			Identifiers.require(s.id());
			Inputs.text(s.title(), 128);
			Inputs.require(ids.add(s.id()), "组件标识重复");
		}
		for (var a : input.actions()) {
			Inputs.require(a != null && a.kind() != null, "动作类型缺失");
			Identifiers.require(a.id());
			Inputs.text(a.label(), 64);
			Inputs.require(ids.add(a.id()), "组件标识重复");
		}
	}

	/** 定义内容不可变，最大50版本使管理操作锁范围有界。 */
	public View create(Actor actor, String key, Definition input) {
		var permit = access.scope(actor, OPS_PAGE_CREATE);
		validate(input);
		var store = stores.requireActive(actor, input.storeId());
		var frozen = JsonCodec.read(JsonCodec.write(input), Definition.class);
		Object command = permit.identity() == null ? frozen : new Object[] {frozen, permit.identity()};
		return commands.runGuarded(actor, "ops-page.create", key, command, View.class,
				() -> { stores.lockCurrent(actor, store); access.lock(permit); }, () -> {
			var versions = mapper.lockGroup(actor.tenantId(), frozen.pageId());
			Inputs.require(versions.size() < 50, "页面版本最多50个");
			mapper.insert(actor.tenantId(), frozen, JsonCodec.write(frozen));
			access.auditVersion(actor, permit, "ops-page.create", key, frozen.pageId(), frozen.version());
			return view(mapper.find(actor.tenantId(), frozen.pageId(), frozen.version()));
		});
	}

	/** 最新版本列表不代表已发布版本，渲染入口另查发布指针。 */
	public List<View> list(Actor actor, String after, int limit) {
		var permit = access.scope(actor, OPS_PAGE_READ);
		Inputs.page(after, limit);
		var result = mapper.list(actor.tenantId(), after, limit).stream().map(this::view).toList();
		EmployeeAccess.requireSame(permit, access.scope(actor, OPS_PAGE_READ));
		return result;
	}

	public List<View> versions(Actor actor, String id) {
		var permit = access.scope(actor, OPS_PAGE_READ);
		Identifiers.require(id);
		var result = mapper.versions(actor.tenantId(), id).stream().map(this::view).toList();
		EmployeeAccess.requireSame(permit, access.scope(actor, OPS_PAGE_READ));
		return result;
	}

	/** 回退只能激活曾经发布过的暂停版本，不能跳过审批。 */
	public View change(Actor actor, String key, String id, long version, long expected, String action) {
		Identifiers.require(id);
		Inputs.require(
				version > 0 && expected >= 0
						&& Set.of("submit", "approve", "reject", "publish", "pause", "rollback").contains(action),
				"页面审批参数无效");
		var capability = switch (action) {
			case "submit" -> OPS_PAGE_SUBMIT; case "approve" -> OPS_PAGE_APPROVE; case "reject" -> OPS_PAGE_REJECT;
			case "publish" -> OPS_PAGE_PUBLISH; case "pause" -> OPS_PAGE_PAUSE; case "rollback" -> OPS_PAGE_ROLLBACK;
			default -> throw conflict("未知页面动作");
		};
		var scope = access.scope(actor, capability);
		var actual = Inputs.found(mapper.find(actor.tenantId(), id, version));
		var permit = access.resource(actor, scope, fact(actual));
		var store = (action.equals("publish") || action.equals("rollback"))
				? stores.requireActive(actor, view(actual).content().storeId()) : stores.fact(actor, view(actual).content().storeId());
		Object command = scope.identity() == null ? List.of(id, version, expected) : List.of(id, version, expected, scope.identity());
		return commands.runGuarded(actor, "ops-page." + action, key, command, View.class,
				() -> { lockPermit(actor, permit); stores.lockCurrent(actor, store); }, () -> {
			var row = locked(actor, id, version);
			boolean allowed = switch (action) {
				case "submit" -> row.status().equals("DRAFT");
				case "approve", "reject" -> row.status().equals("IN_REVIEW");
				case "publish" -> Set.of("APPROVED", "PAUSED").contains(row.status());
				case "rollback" -> row.status().equals("PAUSED");
				case "pause" -> row.status().equals("PUBLISHED");
				default -> false;
			};
			if (!allowed || row.lockVersion() != expected)
				throw conflict("页面状态或版本冲突");
			String next = switch (action) {
				case "submit" -> "IN_REVIEW";
				case "approve" -> "APPROVED";
				case "reject" -> "REJECTED";
				case "publish", "rollback" -> "PUBLISHED";
				default -> "PAUSED";
			};
			if (next.equals("PUBLISHED")) {
				stores.requireActive(actor, view(row).content().storeId());
				mapper.pauseOthers(actor.tenantId(), id);
			}
			if (mapper.change(actor.tenantId(), id, version, expected, next) != 1)
				throw conflict("页面状态并发修改");
			access.auditVersion(actor, scope, "ops-page." + action, key, id, version);
			return view(mapper.find(actor.tenantId(), id, version));
		});
	}

	/** 预览只读真实业务，不持久化未创建页面事实。 */
	public Render preview(Actor actor, Definition input) {
		var prepared = preparePreview(actor, input);
		return prepared.render(legacyActors(actor, prepared.capabilities()));
	}

	public PreparedRender preparePreview(Actor actor, Definition input) {
		var permit = access.scope(actor, OPS_PAGE_PREVIEW);
		validate(input);
		stores.requireActive(actor, input.storeId());
		var page = new View(JsonCodec.read(JsonCodec.write(input), Definition.class), "PREVIEW", 0);
		return preparedRender(actor, page, true, permit);
	}

	/** 发布页面与各段业务能力独立，数据缺少权限时不执行对应查询。 */
	public Render render(Actor actor, String id) {
		var prepared = prepareRender(actor, id);
		return prepared.render(legacyActors(actor, prepared.capabilities()));
	}

	public PreparedRender prepareRender(Actor actor, String id) {
		Identifiers.require(id);
		var scope = access.scope(actor, OPS_PAGE_READ);
		var row = Inputs.found(mapper.published(actor.tenantId(), id));
		access.resource(actor, scope, fact(row));
		return preparedRender(actor, view(row), false, scope);
	}

	private PreparedRender preparedRender(Actor actor, View page, boolean preview, EmployeeAccess.ScopePermit permit) {
		var capabilities = new LinkedHashSet<EmployeeAccess.Capability>();
		page.content().sections().forEach(s -> capabilities.add(sectionCapability(s.source())));
		return new PreparedRender() {
			public Set<EmployeeAccess.Capability> capabilities() { return Set.copyOf(capabilities); }
			public Render render(Map<EmployeeAccess.Capability, Actor> actors) {
				// 先完整核对所有段，任意缺权不能产生其数据或泄露统计。
				var childPermits = new EnumMap<EmployeeAccess.Capability, EmployeeAccess.ScopePermit>(EmployeeAccess.Capability.class);
                for (var capability : capabilities) childPermits.put(capability, requireChild(actor, permit, actors.get(capability), capability));
				var data = page.content().sections().stream().map(s -> {
					var child = actors.get(sectionCapability(s.source()));
					return new SectionData(s.id(), switch (s.source()) {
						case CAMPAIGNS -> campaigns.list(child, "", 20);
						case JOURNEYS -> journeys.definitions(child, "", 20);
						case BUDGETS -> budgets.budgets(child, "", 20);
						case COUPONS -> coupons.definitions(child, page.content().storeId(), "", 20);
						case ENTITLEMENTS -> benefits.definitions(child, page.content().storeId(), "", 20);
					});
				}).toList();
				// 聚合出口重新核对每个原子来源，防止后段查询期间前段授权撤销仍泄露旧结果。
                for(var capability:capabilities) EmployeeAccess.requireSame(childPermits.get(capability), requireChild(actor, permit, actors.get(capability), capability));
                EmployeeAccess.requireSame(permit, access.scope(actor, permit.capability()));
				if (!preview) {
					var current = Inputs.found(mapper.published(actor.tenantId(), page.content().pageId()));
					if (!view(current).equals(page)) throw conflict("页面发布内容已变化");
				}
				return new Render(page, data, preview, true);
			}
		};
	}

	/** 精确动作选择来自实际不可变发布内容，远程授权和目标准备全部先于锁。 */
	public PreparedExecution prepareExecution(Actor actor, String key, String id, long version, String action, ActionInput requested) {
		Identifiers.require(id); Identifiers.require(action);
		Inputs.require(requested != null && version > 0, "页面动作入参无效");
		var input = JsonCodec.read(JsonCodec.write(requested), ActionInput.class);
		var scope = access.scope(actor, OPS_PAGE_EXECUTE);
		var actual = Inputs.found(mapper.find(actor.tenantId(), id, version));
		var permit = access.resource(actor, scope, fact(actual));
		if (!actual.status().equals("PUBLISHED")) throw conflict("页面版本未发布或已被替换");
		var fixedPage = view(actual);
        var page = fixedPage.content();
        var fixedStore = stores.requireActive(actor, page.storeId());
		var choice = page.actions().stream().filter(a -> a.id().equals(action)).findFirst()
				.orElseThrow(() -> new DomainException(DomainException.Code.NOT_FOUND, "页面未声明此动作"));
		int count = (input.campaign() == null ? 0 : 1) + (input.coupon() == null ? 0 : 1) + (input.enrollment() == null ? 0 : 1);
		Inputs.require(count == 1, "动作必须且只能携带一种业务请求");
		var target = switch(choice.kind()) { case CREATE_CAMPAIGN -> CAMPAIGN_CREATE; case CREATE_COUPON -> COUPON_DEFINITION_CREATE; case ENROLL_JOURNEY -> JOURNEY_INSTANCE_CREATE; };
		String childKey = JsonCodec.hash(id + "/" + version + "/" + action + "/" + key);
		return new PreparedExecution() {
			public EmployeeAccess.Capability capability() { return target; }
			public ActionResult execute(Actor child) {
				requireChild(actor, scope, child, target);
				java.util.function.Supplier<ActionResult> prepared;
				switch(choice.kind()) {
					case CREATE_CAMPAIGN -> {
						Inputs.require(input.campaign() != null && page.storeId().equals(input.campaign().storeId()), "活动请求或店铺不匹配");
						var creation = campaigns.prepareCreate(child, childKey, input.campaign());
						prepared = () -> { var result = creation.execute(); return new ActionResult(choice.kind(), result.content().campaignId(), result.status()); };
					}
					case CREATE_COUPON -> {
						Inputs.require(input.coupon() != null && page.storeId().equals(input.coupon().storeId()), "券请求或店铺不匹配");
						var creation = coupons.prepareDefinition(child, childKey, input.coupon());
						prepared = () -> { var result = creation.execute(); return new ActionResult(choice.kind(), result.content().definitionId(), "CREATED"); };
					}
					case ENROLL_JOURNEY -> {
						Inputs.require(input.enrollment() != null, "旅程入组请求缺失");
						var enrollment = journeys.prepareEnroll(child, childKey, input.enrollment());
						prepared = () -> { var result = enrollment.execute(); return new ActionResult(choice.kind(), result.instanceId(), result.status().name()); };
					}
					default -> throw conflict("未登记页面动作");
				}
				Object command = scope.identity() == null ? List.of(id, version, action, input) : List.of(id, version, action, input, scope.identity());
				return commands.runGuarded(actor, "ops-page.execute", key, command, ActionResult.class,
						() -> { lockPermit(actor, permit); stores.lockCurrent(actor, fixedStore); var row = locked(actor, id, version);
                            if (!view(row).equals(fixedPage) || !row.status().equals("PUBLISHED")) throw conflict("页面版本或治理状态已变化"); }, () -> {
					var result = prepared.get();
					access.auditVersion(actor, scope, "ops-page.execute", key, id, version);
					return result;
				});
			}
		};
	}

	public ActionResult execute(Actor actor, String key, String id, long version, String action, ActionInput input) {
		return prepareExecution(actor, key, id, version, action, input).execute(actor);
	}

	/** 同一 HUMAN 与租户可组合各独立能力，不能借另一员工、平台身份或 root ADMIN。 */
	private EmployeeAccess.ScopePermit requireChild(Actor actor, EmployeeAccess.ScopePermit parent, Actor child, EmployeeAccess.Capability capability) {
		if (child == null || !actor.tenantId().equals(child.tenantId()) || !actor.actorId().equals(child.actorId()) || actor.role() != child.role()) throw conflict("页面子动作身份不匹配");
		var permit = access.scope(child, capability);
		if (!Objects.equals(parent.identity(), permit.identity())) throw new DomainException(DomainException.Code.FORBIDDEN, "页面子动作主体不匹配");
        return permit;
	}

	private Map<EmployeeAccess.Capability, Actor> legacyActors(Actor actor, Set<EmployeeAccess.Capability> capabilities) {
		var result = new EnumMap<EmployeeAccess.Capability, Actor>(EmployeeAccess.Capability.class);
		capabilities.forEach(capability -> result.put(capability, actor));
		return result;
	}

	private EmployeeAccess.Capability sectionCapability(Source source) {
		return switch(source) { case CAMPAIGNS -> CAMPAIGN_READ; case JOURNEYS -> JOURNEY_READ; case BUDGETS -> BUDGET_READ; case COUPONS -> COUPON_DEFINITION_READ; case ENTITLEMENTS -> ENTITLEMENT_DEFINITION_READ; };
	}

	private EmployeeAccess.ResourceFact fact(OpsPageMapper.Row row) { return new EmployeeAccess.ResourceFact("ops_page", row.pageId(), row.version()); }

	/** 原内容不可变，锁实际版本并锁接管路由，状态动作另用原 lockVersion CAS。 */
	private void lockPermit(Actor actor, EmployeeAccess.ResourcePermit permit) {
		var row = locked(actor, permit.fact().id(), permit.fact().version());
		if (!fact(row).equals(permit.fact())) throw conflict("页面授权内容已变化");
		access.lock(permit.scope());
	}

	private OpsPageMapper.Row locked(Actor actor, String id, long version) {
		return mapper.lockGroup(actor.tenantId(), id)
			.stream()
			.filter(r -> r.version() == version)
			.findFirst()
			.orElseThrow(() -> new DomainException(DomainException.Code.NOT_FOUND, "页面版本不存在"));
	}

	private View view(OpsPageMapper.Row row) {
		return new View(JsonCodec.read(row.definitionJson(), Definition.class), row.status(), row.lockVersion());
	}

	private DomainException conflict(String message) {
		return new DomainException(DomainException.Code.CONFLICT, message);
	}

}
