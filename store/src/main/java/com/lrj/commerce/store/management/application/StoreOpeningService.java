package com.lrj.commerce.store.management.application;

import static com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.STORE_CREATE;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.kernel.Identifiers;
import com.lrj.commerce.merchant.api.MerchantApi;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.api.validation.Inputs;
import com.lrj.commerce.runtime.audit.OperationAudit;
import com.lrj.commerce.runtime.audit.OperationAudit.Action;
import com.lrj.commerce.runtime.audit.OperationAudit.Change;
import com.lrj.commerce.runtime.audit.OperationAudit.Committed;
import com.lrj.commerce.runtime.audit.OperationAudit.Evidence;
import com.lrj.commerce.runtime.command.Commands;
import com.lrj.commerce.store.management.api.StoreOpeningApi;
import com.lrj.commerce.store.management.infrastructure.persistence.StoreOpeningMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** 开店审核引用、门店状态与身份审计一起提交，重复命令只返回原回执。 */
@Service
public class StoreOpeningService implements StoreOpeningApi {

	private static final String VERSION_CONFLICT = "开店资料或状态已变化，请刷新后重试";
	private static final String CATEGORY_UNAVAILABLE = "请选择当前可用的经营类目";

	private final StoreOpeningMapper mapper;
	private final MerchantApi merchants;
	private final Commands commands;
	private final EmployeeAccess access;
	private final OperationAudit audit;

	public StoreOpeningService(StoreOpeningMapper mapper, MerchantApi merchants, Commands commands,
			EmployeeAccess access, OperationAudit audit) {
		this.mapper = mapper;
		this.merchants = merchants;
		this.commands = commands;
		this.access = access;
		this.audit = audit;
	}

	/** 目录读取沿用开店权限，避免将其当作公开的经营授权。 */
	@Override
	public List<Category> categories(Actor actor) {
		var permit = access.scope(actor, STORE_CREATE);
		var categories = mapper.categories();
		EmployeeAccess.requireSame(permit, access.scope(actor, STORE_CREATE));
		return categories;
	}

	/** 保存草稿时校验真实商家归属，避免创建可交易门店。 */
	@Override
	public Opening draft(Actor actor, String key, Draft input) {
		return draft(actor, key, input, null);
	}

	/** 尝试先写入，防止草稿无效或权限拒绝时丢失请求。 */
	@Override
	public Opening draft(Actor actor, String key, Draft input, String traceId) {
		String reference = input == null ? null : auditReference(input.storeId());
		return audit.capture(Action.STORE_OPENING_DRAFT, actor, reference, traceId,
				attempt -> draftAudited(actor, key, input, attempt));
	}

	private Opening draftAudited(Actor actor, String key, Draft input, long attemptId) {
		Inputs.require(input != null, "请求不能为空");
		var draft = new Draft(Identifiers.require(input.storeId()), Identifiers.require(input.merchantId()),
				Inputs.text(input.name(), 128).strip(), subjectType(input.subjectType()),
				legacyCategory(input.categoryCode(), input.category()), categoryCode(input.categoryCode()));
		return run(actor, key, Action.STORE_OPENING_DRAFT, draft.storeId(), draft, attemptId, () -> {
			merchants.requireActive(actor, draft.merchantId());
			mapper.insert(actor.tenantId(), draft, categoryLabel(draft.categoryCode(), draft.category()));
			return evidence(Action.STORE_OPENING_DRAFT, null, opening(actor, draft.storeId()));
		});
	}

	/** 二次核对目录路由与许可，资料查询不会扩大目录的读取范围。 */
	@Override
	public Opening detail(Actor actor, String storeId) {
		var permit = access.scope(actor, STORE_CREATE);
		var value = opening(actor, Identifiers.require(storeId));
		EmployeeAccess.requireSame(permit, access.scope(actor, STORE_CREATE));
		return value;
	}

	/** 授权随当前店铺资料核对，查询后再次验证权威范围。 */
	@Override
	public OperationAudit.Page history(Actor actor, String storeId, String traceId, String cursor) {
		var permit = access.scope(actor, STORE_CREATE);
		String reference = Identifiers.require(storeId);
		opening(actor, reference);
		var page = audit.openingHistory(actor, reference, traceId, cursor);
		EmployeeAccess.requireSame(permit, access.scope(actor, STORE_CREATE));
		return page;
	}

	/** 已分配编号和商家归属保持固定，申请主体、主营类目和名称可在审核前补正。 */
	@Override
	public Opening revise(Actor actor, String key, String storeId, Revision input) {
		return revise(actor, key, storeId, input, null);
	}

	/** 补正使用已有幂等命令边界。 */
	@Override
	public Opening revise(Actor actor, String key, String storeId, Revision input, String traceId) {
		return audit.capture(Action.STORE_OPENING_REVISE, actor, auditReference(storeId), traceId,
				attempt -> reviseAudited(actor, key, storeId, input, attempt));
	}

	private Opening reviseAudited(Actor actor, String key, String storeId, Revision input, long attemptId) {
		Identifiers.require(storeId);
		Inputs.require(input != null, "请求不能为空");
		requireVersion(input.expectedVersion());
		var revision = new Revision(input.expectedVersion(), Inputs.text(input.name(), 128).strip(),
				subjectType(input.subjectType()), legacyCategory(input.categoryCode(), input.category()),
				categoryCode(input.categoryCode()));
		return run(actor, key, Action.STORE_OPENING_REVISE, storeId, revision, attemptId, () -> {
			var before = Inputs.found(mapper.lock(actor.tenantId(), storeId));
			requireUpdated(mapper.revise(actor.tenantId(), storeId, revision,
					categoryLabel(revision.categoryCode(), revision.category())));
			return evidence(Action.STORE_OPENING_REVISE, before, opening(actor, storeId));
		});
	}

	/** 只有审核对应的草稿版本能启用，确认时重新校验所属商家仍可经营。 */
	@Override
	public Opening open(Actor actor, String key, String storeId, Activation input) {
		return open(actor, key, storeId, input, null);
	}

	/** 记录开通尝试，提交结果沿用既有审核与状态限制。 */
	@Override
	public Opening open(Actor actor, String key, String storeId, Activation input, String traceId) {
		return audit.capture(Action.STORE_OPENING_OPEN, actor, auditReference(storeId), traceId,
				attempt -> openAudited(actor, key, storeId, input, attempt));
	}

	private Opening openAudited(Actor actor, String key, String storeId, Activation input, long attemptId) {
		Identifiers.require(storeId);
		Inputs.require(input != null, "请求不能为空");
		requireVersion(input.expectedVersion());
		var activation = new Activation(input.expectedVersion(), Identifiers.require(input.reviewReference()));
		return run(actor, key, Action.STORE_OPENING_OPEN, storeId, activation, attemptId, () -> {
			var current = Inputs.found(mapper.lock(actor.tenantId(), storeId));
			merchants.requireActive(actor, current.merchantId());
			categoryLabel(current.categoryCode(), current.category());
			requireUpdated(mapper.open(actor.tenantId(), storeId, actor.actorId(), activation));
			return evidence(Action.STORE_OPENING_OPEN, current, opening(actor, storeId));
		});
	}

	private Opening run(Actor actor, String key, Action operation, String storeId, Object input,
			long attemptId, Supplier<Committed<Opening>> action) {
		var permit = access.scope(actor, STORE_CREATE);
		// 店铺标识参与指纹，同一个请求键不能误返回另一家店的回执。
		var command = new Object[] { storeId, input, permit.identity() };
		return commands.runAudited(actor, operation.code(), key, command, Opening.class, () -> access.lock(permit), attemptId, () -> {
			var value = action.get();
			access.audit(actor, permit, operation.code(), key, storeId);
			return value;
		});
	}

	private static Committed<Opening> evidence(Action action, Opening before, Opening after) {
		var changes = new ArrayList<Change>();
		addChange(changes, "name", before == null ? null : OperationAudit.safeText(before.name()), OperationAudit.safeText(after.name()));
		addChange(changes, "status", before == null ? null : before.status(), after.status());
		addChange(changes, "subjectType", before == null ? null : before.subjectType(), after.subjectType());
		addChange(changes, "categoryCode", before == null ? null : before.categoryCode(), after.categoryCode());
		addChange(changes, "reviewReference", before == null ? null : OperationAudit.safeText(before.reviewReference()),
				OperationAudit.safeText(after.reviewReference()));
		return new Committed<>(after, new Evidence(after.storeId(), before == null ? null : before.version(),
				after.version(), null, changes, action.code()));
	}

	private static void addChange(List<Change> changes, String field, Object before, Object after) {
		if (!Objects.equals(before, after))
			changes.add(new Change(field, before, after));
	}

	private static String auditReference(String value) {
		try {
			return Identifiers.require(value);
		}
		catch (DomainException invalid) {
			return null;
		}
	}

	private Opening opening(Actor actor, String storeId) {
		return Inputs.found(mapper.find(actor.tenantId(), storeId));
	}

	private static String categoryCode(String code) {
		return code == null ? null : Identifiers.require(code);
	}

	/** 老客户端保留原有命令指纹，新客户端只按稳定代码确定类目。 */
	private static String legacyCategory(String code, String label) {
		return code == null ? Inputs.text(label, 128).strip() : null;
	}

	/** 名称由平台字典产生并保留审核快照，禁用或只选大类时阻止新写入。 */
	private String categoryLabel(String code, String legacyLabel) {
		if (code == null)
			return legacyLabel;
		var label = mapper.categoryLabel(code);
		Inputs.require(label != null, CATEGORY_UNAVAILABLE);
		return label;
	}

	private static void requireVersion(Long version) {
		Inputs.require(version != null && version >= 0, "开店资料版本无效");
	}

	private static SubjectType subjectType(SubjectType type) {
		Inputs.require(type != null, "请选择经营主体类型");
		return type;
	}

	private static void requireUpdated(int rows) {
		if (rows != 1)
			throw new DomainException(DomainException.Code.CONFLICT, VERSION_CONFLICT);
	}
}
