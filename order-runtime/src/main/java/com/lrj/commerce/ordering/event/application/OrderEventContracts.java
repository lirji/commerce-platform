package com.lrj.commerce.ordering.event.application;

import com.lrj.commerce.runtime.api.event.UnconsumedEventType;
import org.springframework.context.annotation.*;

/** 订单发布但本进程没有消费者的生命周期事实，写入即SKIPPED并保留供审计与重放。 */
@Configuration(proxyBeanMethods = false)
class OrderEventContracts {

	/** 发货进入履约中：状态以order_record为准，当前没有进程内消费者、外发中继或要求消费者的契约。 */
	@Bean
	UnconsumedEventType orderFulfilling() {
		return new UnconsumedEventType("order.fulfilling.v1", UnconsumedEventType.Reason.NO_REGISTERED_CONSUMER);
	}

}
