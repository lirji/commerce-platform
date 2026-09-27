package com.lrj.commerce.runtime;

import com.lrj.commerce.runtime.api.Actor;
import com.lrj.commerce.runtime.persistence.RecoveryMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

/**
 * 运维恢复审计：必须在恢复命令的事务内调用，与状态变更、命令幂等结果一起提交或一起回滚；
 * 同一幂等键重放命令只返回原结果，不会再次调用这里。只记录标识、状态、分类与原因，不记录载荷或异常文本。
 */
@Component
public class RecoveryAudit {

	public static final String APPLIED = "APPLIED", REJECTED = "REJECTED";

	private final RecoveryMapper mapper;

	public RecoveryAudit(RecoveryMapper mapper) {
		this.mapper = mapper;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void record(Actor actor, String operation, String key, String workType, String workId, String action,
			String previousState, String newState, String failureClass, String reason, String result,
			String rejection) {
		mapper.insert(new RecoveryMapper.Entry(actor.tenantId(), actor.actorId(), operation, key, workType, workId,
				action, previousState, newState, failureClass, reason, result, rejection));
	}

}
