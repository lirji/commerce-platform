package com.lrj.commerce.runtime.event;

import com.lrj.commerce.kernel.Identifiers;
import com.lrj.commerce.runtime.event.persistence.EventMapper;
import org.springframework.stereotype.Component;

/** 业务执行查询只读取现有运行时失败证据，不建立第二套恢复队列。 */
@Component
public class EventInspection {

	public record Fact(String eventId, String status, String failureClass) {
	}

	private final EventMapper mapper;

	public EventInspection(EventMapper mapper) {
		this.mapper = mapper;
	}

	/** 返回聚合事件的有限诊断字段，错误详情仍仅在受控恢复界面查看。 */
	public Fact latest(String tenant, String type, String aggregate) {
		Identifiers.require(tenant);
		Identifiers.require(type);
		Identifiers.require(aggregate);
		var row = mapper.latestFact(tenant, type, aggregate);
		return row == null ? null : new Fact(row.eventId(), row.status(), row.failureClass());
	}

}
