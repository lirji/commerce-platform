package com.lrj.commerce.store.management.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.lrj.commerce.runtime.api.identity.Actor;
import java.time.Instant;
import java.util.List;

/** 平台分类权威。开店和门店商品分类共享同一份可引用目录。 */
public interface PlatformCategoryApi {
	int MAX_DEPTH = 3;

	enum Kind {
		BRANCH, LEAF;

		/** 只接受稳定代码，避免整数与枚举声明顺序绑定。 */
		@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
		public static Kind fromCode(String code) {
			return valueOf(code);
		}
	}

	record Node(String code, String parentCode, String name, Kind kind, int depth, int sortOrder, boolean enabled,
			long version) {
	}

	record Directory(boolean canEdit, List<Node> items) {
	}

	record Create(String code, String parentCode, String name, Kind kind, Integer sortOrder, String reason) {
	}

	record Change(Long expectedVersion, String name, Integer sortOrder, Boolean enabled, String reason) {
	}

	record History(long version, String name, int sortOrder, boolean enabled, String actorId, String reason,
			Instant changedAt) {
	}

	/** 平台界面包含停用节点及当前操作者能否编辑。 */
	Directory directory(Actor actor);

	/** 创建分组或末级，不改变已有类目的父级与编码。 */
	Node create(Actor actor, String key, Create input);

	/** 同版本修改名称、排序与启停，历史快照与写入一起提交。 */
	Node change(Actor actor, String key, String code, Change input);

	/** 列出该类目最近的维护记录。 */
	List<History> history(Actor actor, String code);

	/** 由已判权的开店、商品分类用例读取可选末级类目。 */
	List<StoreOpeningApi.Category> options();

	/** 在调用方写事务中校验并锁定可绑定的末级及祖先。 */
	void requireAssignable(String code);
}
