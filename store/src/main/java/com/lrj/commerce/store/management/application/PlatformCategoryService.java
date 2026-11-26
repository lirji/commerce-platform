package com.lrj.commerce.store.management.application;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.api.validation.Inputs;
import com.lrj.commerce.runtime.command.Commands;
import com.lrj.commerce.store.management.api.PlatformCategoryApi;
import com.lrj.commerce.store.management.api.StoreOpeningApi;
import com.lrj.commerce.store.management.infrastructure.persistence.PlatformCategoryMapper;
import com.lrj.commerce.store.management.infrastructure.persistence.StoreOpeningMapper;
import java.util.List;
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

	private final PlatformCategoryMapper mapper;
	private final StoreOpeningMapper opening;
	private final Commands commands;

	public PlatformCategoryService(PlatformCategoryMapper mapper, StoreOpeningMapper opening, Commands commands) {
		this.mapper = mapper;
		this.opening = opening;
		this.commands = commands;
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
		requirePlatform(actor);
		Inputs.require(input != null && input.kind() != null, "请选择类目类型");
		var request = new Create(code(input.code()), input.parentCode() == null ? null : code(input.parentCode()),
				Inputs.text(input.name(), 56).strip(), input.kind(), sort(input.sortOrder()),
				Inputs.text(input.reason(), 256).strip());
		return commands.runGuarded(actor, CREATE, key, request, Node.class, () -> lockCurator(actor), () -> {
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
			return audited(actor, request.code(), request.reason());
		});
	}

	/** 原地停用保留引用，祖先停用时整个分支会从新选择目录隐藏。 */
	@Override
	public Node change(Actor actor, String key, String id, Change input) {
		requirePlatform(actor);
		String categoryCode = code(id);
		Inputs.require(input != null && input.expectedVersion() != null && input.expectedVersion() >= 0
				&& input.enabled() != null, "类目状态或版本无效");
		var request = new Change(input.expectedVersion(), Inputs.text(input.name(), 56).strip(),
				sort(input.sortOrder()), input.enabled(), Inputs.text(input.reason(), 256).strip());
		return commands.runGuarded(actor, CHANGE, key, new Object[] { categoryCode, request }, Node.class,
				() -> lockCurator(actor), () -> {
					Inputs.found(mapper.find(categoryCode));
					if (mapper.change(categoryCode, request) != 1)
						throw new DomainException(DomainException.Code.CONFLICT, "类目已变化，请刷新后重试");
					return audited(actor, categoryCode, request.reason());
				});
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
		if (mapper.audit(id, actor.tenantId(), actor.actorId(), reason) != 1)
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
		Inputs.require(value != null && value.matches("[A-Z][A-Z0-9_]{0,63}"), "类目编码须为大写字母、数字或下划线，并以字母开头");
		return value;
	}

	private static int sort(Integer value) {
		Inputs.require(value != null && value >= 0 && value <= MAX_SORT_ORDER, "类目排序值无效");
		return value;
	}
}
