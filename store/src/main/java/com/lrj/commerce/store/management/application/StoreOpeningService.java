package com.lrj.commerce.store.management.application;

import static com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.STORE_CREATE;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.kernel.Identifiers;
import com.lrj.commerce.merchant.api.MerchantApi;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.api.validation.Inputs;
import com.lrj.commerce.runtime.command.Commands;
import com.lrj.commerce.store.management.api.StoreOpeningApi;
import com.lrj.commerce.store.management.infrastructure.persistence.StoreOpeningMapper;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** 开店审核引用、门店状态与身份审计一起提交，重复命令只返回原回执。 */
@Service
public class StoreOpeningService implements StoreOpeningApi {

	private static final String DRAFT = "store.opening.draft";
	private static final String REVISE = "store.opening.revise";
	private static final String OPEN = "store.opening.open";
	private static final String VERSION_CONFLICT = "开店资料或状态已变化，请刷新后重试";
	private static final String CATEGORY_UNAVAILABLE = "请选择当前可用的经营类目";

	private final StoreOpeningMapper mapper;
	private final MerchantApi merchants;
	private final Commands commands;
	private final EmployeeAccess access;

	public StoreOpeningService(StoreOpeningMapper mapper, MerchantApi merchants, Commands commands,
			EmployeeAccess access) {
		this.mapper = mapper;
		this.merchants = merchants;
		this.commands = commands;
		this.access = access;
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
		Inputs.require(input != null, "请求不能为空");
		var draft = new Draft(Identifiers.require(input.storeId()), Identifiers.require(input.merchantId()),
				Inputs.text(input.name(), 128).strip(), subjectType(input.subjectType()),
				legacyCategory(input.categoryCode(), input.category()), categoryCode(input.categoryCode()));
		return run(actor, key, DRAFT, draft.storeId(), draft, () -> {
			merchants.requireActive(actor, draft.merchantId());
			mapper.insert(actor.tenantId(), draft, categoryLabel(draft.categoryCode(), draft.category()));
			return opening(actor, draft.storeId());
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

	/** 已分配编号和商家归属保持固定，申请主体、主营类目和名称可在审核前补正。 */
	@Override
	public Opening revise(Actor actor, String key, String storeId, Revision input) {
		Identifiers.require(storeId);
		Inputs.require(input != null, "请求不能为空");
		requireVersion(input.expectedVersion());
		var revision = new Revision(input.expectedVersion(), Inputs.text(input.name(), 128).strip(),
				subjectType(input.subjectType()), legacyCategory(input.categoryCode(), input.category()),
				categoryCode(input.categoryCode()));
		return run(actor, key, REVISE, storeId, revision, () -> {
			opening(actor, storeId);
			requireUpdated(mapper.revise(actor.tenantId(), storeId, revision,
					categoryLabel(revision.categoryCode(), revision.category())));
			return opening(actor, storeId);
		});
	}

	/** 只有审核对应的草稿版本能启用，确认时重新校验所属商家仍可经营。 */
	@Override
	public Opening open(Actor actor, String key, String storeId, Activation input) {
		Identifiers.require(storeId);
		Inputs.require(input != null, "请求不能为空");
		requireVersion(input.expectedVersion());
		var activation = new Activation(input.expectedVersion(), Identifiers.require(input.reviewReference()));
		return run(actor, key, OPEN, storeId, activation, () -> {
			var current = opening(actor, storeId);
			merchants.requireActive(actor, current.merchantId());
			categoryLabel(current.categoryCode(), current.category());
			requireUpdated(mapper.open(actor.tenantId(), storeId, actor.actorId(), activation));
			return opening(actor, storeId);
		});
	}

	private Opening run(Actor actor, String key, String operation, String storeId, Object input,
			Supplier<Opening> action) {
		var permit = access.scope(actor, STORE_CREATE);
		// 店铺标识参与指纹，同一个请求键不能误返回另一家店的回执。
		var command = new Object[] { storeId, input, permit.identity() };
		return commands.runGuarded(actor, operation, key, command, Opening.class, () -> access.lock(permit), () -> {
			var value = action.get();
			access.audit(actor, permit, operation, key, storeId);
			return value;
		});
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
