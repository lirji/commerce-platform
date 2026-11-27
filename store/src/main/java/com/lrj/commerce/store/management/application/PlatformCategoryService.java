package com.lrj.commerce.store.management.application;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.api.validation.Inputs;
import com.lrj.commerce.runtime.audit.OperationAudit;
import com.lrj.commerce.runtime.audit.OperationAudit.Action;
import com.lrj.commerce.runtime.audit.OperationAudit.Committed;
import com.lrj.commerce.runtime.audit.OperationAudit.Evidence;
import com.lrj.commerce.runtime.command.Commands;
import com.lrj.commerce.store.management.api.PlatformCategoryApi;
import com.lrj.commerce.store.management.api.StoreOpeningApi;
import com.lrj.commerce.store.management.infrastructure.persistence.PlatformCategoryMapper;
import com.lrj.commerce.store.management.infrastructure.persistence.StoreOpeningMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 字典维护、授权和历史落在同一个事务，店铺和商品只读取可绑定的类目。 */
@Service
public class PlatformCategoryService implements PlatformCategoryApi {
	private static final int MAX_SORT_ORDER = 65535;
	private static final String CREATE = "platform.category.create";
	private static final String CHANGE = "platform.category.change";
	private static final String CURATOR_REQUIRED = "需要平台类目维护权限";
	private static final String CATEGORY_UNAVAILABLE = "请选择当前可用的末级类目";
	private static final String CATEGORY_CODE = "[A-Z][A-Z0-9_]{0,63}";

	private final PlatformCategoryMapper mapper;
	private final StoreOpeningMapper opening;
	private final Commands commands;
	private final OperationAudit audit;

	public PlatformCategoryService(PlatformCategoryMapper mapper, StoreOpeningMapper opening, Commands commands,
			OperationAudit audit) {
		this.mapper = mapper;
		this.opening = opening;
		this.commands = commands;
		this.audit = audit;
	}

	/** 平台运维可读字典，只有单独授予的类目专岗可以写。 */
	@Override
	public Directory directory(Actor actor) {
		requirePlatform(actor);
		return new Directory(mapper.curator(actor.tenantId(), actor.actorId()), mapper.list());
	}

	/** 锁定有效分组及上级后计算深度，避免同停用操作并发时越界。 */
	@Override
	public Node create(Actor actor, String key, Create input) {
		return create(actor, key, input, null);
	}

	/** 先记录请求，再校验和锁定受影响类目。 */
	@Override
	public Node create(Actor actor, String key, Create input, String traceId) {
		requirePlatform(actor);
		String reference = input != null && input.code() != null && input.code().matches(CATEGORY_CODE)
				? input.code() : null;
		return audit.capture(Action.CATEGORY_CREATE, actor, reference, traceId,
				attempt -> createAudited(actor, key, input, attempt));
	}

	private Node createAudited(Actor actor, String key, Create input, long attemptId) {
		Inputs.require(input != null && input.kind() != null, "请选择类目类型");
		var request = new Create(code(input.code()), input.parentCode() == null ? null : code(input.parentCode()),
				Inputs.text(input.name(), 56).strip(), input.kind(), sort(input.sortOrder()),
				Inputs.text(input.reason(), 256).strip());
		return commands.runAudited(actor, CREATE, key, request, Node.class, () -> lockCurator(actor), attemptId, () -> {
			int depth = 1;
			if (request.parentCode() != null) {
				var parent = mapper.parentForCreate(request.parentCode());
				Inputs.require(parent != null, "请选择可用的分组作为上级类目");
				depth = parent.depth() + 1;
			}
			Inputs.require(depth <= MAX_DEPTH, "平台类目最多三级");
			Inputs.require(depth != 1 || request.kind() == Kind.BRANCH, "一级类目必须是分组");
			Inputs.require(depth != MAX_DEPTH || request.kind() == Kind.LEAF, "第三级必须是末级类目");
			mapper.insert(request, depth);
			Node after = audited(actor, request.code(), request.reason());
			var changes = List.of(new OperationAudit.Change("name", null, OperationAudit.safeText(after.name())),
					new OperationAudit.Change("sortOrder", null, after.sortOrder()),
					new OperationAudit.Change("enabled", null, after.enabled()));
			return new Committed<>(after, new Evidence(after.code(), null, after.version(), request.reason(), changes,
					Action.CATEGORY_CREATE.code()));
		});
	}

	/** 原地停用保留引用，祖先停用时整个分支会从新选择目录隐藏。 */
	@Override
	public Node change(Actor actor, String key, String id, Change input) {
		return change(actor, key, id, input, null);
	}

	/** 保持原幂等命令键；只有获平台目录读取权限的请求能记录合法格式的类目号。 */
	@Override
	public Node change(Actor actor, String key, String id, Change input, String traceId) {
		requirePlatform(actor);
		String reference = id != null && id.matches(CATEGORY_CODE) ? id : null;
		Action action = input != null && Boolean.FALSE.equals(input.enabled()) ? Action.CATEGORY_DISABLE
				: Action.CATEGORY_CHANGE;
		return audit.capture(action, actor, reference, traceId, attempt -> changeAudited(actor, key, id, input, attempt));
	}

	private Node changeAudited(Actor actor, String key, String id, Change input, long attemptId) {
		String categoryCode = code(id);
		Inputs.require(input != null && input.expectedVersion() != null && input.expectedVersion() >= 0
				&& input.enabled() != null, "类目状态或版本无效");
		var request = new Change(input.expectedVersion(), Inputs.text(input.name(), 56).strip(),
				sort(input.sortOrder()), input.enabled(), Inputs.text(input.reason(), 256).strip());
		return commands.runAudited(actor, CHANGE, key, new Object[] { categoryCode, request }, Node.class,
				() -> lockCurator(actor), attemptId, () -> {
					Node before = Inputs.found(mapper.lock(categoryCode));
					Node after = applyChange(actor, categoryCode, request);
					return new Committed<>(after, evidence(before, after, request.reason()));
				});
	}

	private Node applyChange(Actor actor, String code, Change request) {
		if (mapper.change(code, request) != 1)
			throw new DomainException(DomainException.Code.CONFLICT, "类目已变化，请刷新后重试");
		return audited(actor, code, request.reason());
	}

	private static Evidence evidence(Node before, Node after, String reason) {
		var changes = new ArrayList<OperationAudit.Change>(3);
		boolean renamed = !Objects.equals(before.name(), after.name());
		boolean reordered = before.sortOrder() != after.sortOrder();
		boolean toggled = before.enabled() != after.enabled();
		if (renamed)
			changes.add(new OperationAudit.Change("name", OperationAudit.safeText(before.name()),
					OperationAudit.safeText(after.name())));
		if (reordered)
			changes.add(new OperationAudit.Change("sortOrder", before.sortOrder(), after.sortOrder()));
		if (toggled)
			changes.add(new OperationAudit.Change("enabled", before.enabled(), after.enabled()));
		Action primary = Action.CATEGORY_CHANGE;
		if (toggled)
			primary = after.enabled() ? Action.CATEGORY_ENABLE : Action.CATEGORY_DISABLE;
		else if (renamed)
			primary = Action.CATEGORY_RENAME;
		else if (reordered)
			primary = Action.CATEGORY_REORDER;
		return new Evidence(after.code(), before.version(), after.version(), reason, changes, primary.code());
	}

	/** 已受信的平台后台可以复核版本、操作者和变更原因。 */
	@Override
	public List<History> history(Actor actor, String id) {
		requirePlatform(actor);
		String categoryCode = code(id);
		Inputs.found(mapper.find(categoryCode));
		return mapper.history(categoryCode);
	}

	/** 开店和商品用例使用一致的可选路径。 */
	@Override
	public List<StoreOpeningApi.Category> options() {
		return opening.categories();
	}

	/** 共享锁让停用与保存商品分类引用顺序可判定。 */
	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void requireAssignable(String id) {
		Inputs.require(opening.categoryLabel(code(id)) != null, CATEGORY_UNAVAILABLE);
	}

	private Node audited(Actor actor, String id, String reason) {
		if (mapper.audit(id, actor.tenantId(), actor.actorId(), OperationAudit.safeText(reason)) != 1)
			throw new DomainException(DomainException.Code.CONFLICT, "类目变更记录写入失败");
		return Inputs.found(mapper.find(id));
	}

	private void lockCurator(Actor actor) {
		if (!Boolean.TRUE.equals(mapper.lockCurator(actor.tenantId(), actor.actorId())))
			throw new DomainException(DomainException.Code.FORBIDDEN, CURATOR_REQUIRED);
	}

	private static void requirePlatform(Actor actor) {
		if (actor == null || actor.role() != Actor.Role.PLATFORM_OPERATOR)
			throw new DomainException(DomainException.Code.FORBIDDEN, CURATOR_REQUIRED);
	}

	private static String code(String value) {
		Inputs.require(value != null && value.matches(CATEGORY_CODE), "类目编码须为大写字母、数字或下划线，并以字母开头");
		return value;
	}

	private static int sort(Integer value) {
		Inputs.require(value != null && value >= 0 && value <= MAX_SORT_ORDER, "类目排序值无效");
		return value;
	}
}
