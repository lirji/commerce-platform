package com.lrj.commerce.runtime.command;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.kernel.Identifiers;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.command.persistence.CommandMapper;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.lrj.commerce.runtime.serialization.JsonCodec;

/** 命令回放、业务效果和审计同一事务，崩溃不能留下成功回执但没有业务效果。 */
@Component
public class Commands {

	private final CommandMapper mapper;

	private final TransactionTemplate transaction;

	public Commands(CommandMapper mapper, PlatformTransactionManager manager) {
		this.mapper = mapper;
		this.transaction = new TransactionTemplate(manager);
		this.transaction.setTimeout(10);
	}

	/** Owner已核对当前身份/资源后，在事务外选择旧回执的原方向；真正返回仍必须通过runGuarded栅栏。 */
	public <T> T completedReceipt(Actor actor, String operation, String commandKey, Object input, Class<T> type) {
		Identifiers.require(operation);
		Identifiers.require(commandKey);
		var row = mapper.find(new CommandMapper.Key(actor.tenantId(), actor.actorId(), operation, commandKey));
		if (row == null || row.responseJson() == null) return null;
		if (!row.requestHash().equals(JsonCodec.hash(JsonCodec.write(input))))
			throw new DomainException(DomainException.Code.IDEMPOTENCY_CONFLICT, "相同幂等键的请求内容不同");
		return JsonCodec.read(row.responseJson(), type);
	}

	/** 唯一键争用在数据库内串行；相同键不同业务输入不得重新执行。 */
	public <T> T run(Actor actor, String operation, String commandKey, Object input, Class<T> type,
			Supplier<T> action) {
		return runGuarded(actor, operation, commandKey, input, type, () -> {}, action);
	}

	/** 权威切换栅栏先于旧回执读取，防止幂等重试绕过当前资源与身份限制。 */
	public <T> T runGuarded(Actor actor, String operation, String commandKey, Object input, Class<T> type,
			Runnable guard, Supplier<T> action) {
		return runGuarded(actor, operation, commandKey, input, type, guard, result -> {}, action);
	}

	/** 持久任务回执仍关联原来源，返回旧任务前由Owner核对来源，不能以新引用复活旧任务。 */
	public <T> T runGuarded(Actor actor, String operation, String commandKey, Object input, Class<T> type,
			Runnable guard, java.util.function.Consumer<T> receiptGuard, Supplier<T> action) {
		Identifiers.require(commandKey);
		Identifiers.require(operation);
		var key = new CommandMapper.Key(actor.tenantId(), actor.actorId(), operation, commandKey);
		String hash = JsonCodec.hash(JsonCodec.write(input));
		return transaction.execute(status -> {
			guard.run();
			mapper.claim(key, hash);
			var previous = mapper.lock(key);
			if (!previous.requestHash().equals(hash)) {
				throw new DomainException(DomainException.Code.IDEMPOTENCY_CONFLICT, "相同幂等键的请求内容不同");
			}
			if (previous.responseJson() != null) {
				T result = JsonCodec.read(previous.responseJson(), type);
				receiptGuard.accept(result);
				return result;
			}
			T result = action.get();
			if (mapper.complete(key, JsonCodec.write(result)) != 1)
				throw new IllegalStateException("命令结果写入失败");
			mapper.audit(key);
			return result;
		});
	}

}
