package com.lrj.commerce.merchant.api;

import com.lrj.commerce.runtime.api.validation.ListFilter;
import com.lrj.commerce.runtime.api.identity.Actor;
import java.util.List;

/** Merchant边界只暴露不可变业务投影，表由本模块独占。 */
public interface MerchantApi {

	record Create(String merchantId, String name) {
	}

	record View(String merchantId, String name, String status, long version) {
	}

	/** 同一命令重试返回原结果。 */
	View create(Actor actor, String key, Create input);

	/** 从可信租户限定资源，禁止跨租户访问。 */
	View requireActive(Actor actor, String id);

	/** 有界游标分页。 */
	List<View> list(Actor actor, String after, int limit);

	/** 只读查询条件，不改变既有数据权限或业务记录。 */
	List<View> list(Actor actor, String after, int limit, ListFilter filter);

}
