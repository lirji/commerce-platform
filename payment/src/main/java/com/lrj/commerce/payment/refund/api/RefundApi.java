package com.lrj.commerce.payment.refund.api;

import com.lrj.commerce.runtime.api.validation.ListFilter;
import com.lrj.commerce.runtime.api.identity.Actor;
import java.util.List;

/** 退款是新资金效果，不回退原支付记录；UNKNOWN不能展示为成功。 */
public interface RefundApi {

	record View(String refundId, String caseId, String orderId, String amount, String currency, String provider,
			String status, long version, String businessNo) {
	}

	/** 运营列表和详情共用编号投影；退款事件和写命令沿用原视图。 */
	record Detail(String refundId, String caseId, String orderId, String amount, String currency,
			String provider, String status, long version, String businessNo, String orderBusinessNo, String caseBusinessNo,
			String storeId, java.time.Instant createdAt) {
	}

	/** 按业务编号或原编号读取当前事实，写岗位资格独立。 */
	Detail adminDetail(Actor actor, String id);

	View request(String tenant, String caseId, String orderId, String amount);

	View internalRead(String tenant, String refundId);

	List<Detail> list(Actor actor, String after, int limit);

	/** 只读查询条件，不改变既有数据权限或业务记录。 */
	List<Detail> list(Actor actor, String after, int limit, ListFilter filter);

	View reconcile(Actor actor, String refundId);

	View sandboxSuccess(Actor actor, String key, String refundId);

	int tick();

	record Total(String orderId, String amount) {
	}

	/** 内部有界退款汇总，仅SUCCEEDED终态金额可进入分析投影。 */
	List<Total> totals(String tenant, List<String> orderIds);

}
