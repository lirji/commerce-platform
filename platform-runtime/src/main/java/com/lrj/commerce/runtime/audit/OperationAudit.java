package com.lrj.commerce.runtime.audit;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.api.validation.Inputs;
import com.lrj.commerce.runtime.audit.persistence.OperationAuditMapper;
import com.lrj.commerce.runtime.audit.persistence.OperationAuditMapper.Attempt;
import com.lrj.commerce.runtime.audit.persistence.OperationAuditMapper.Query;
import com.lrj.commerce.runtime.audit.persistence.OperationAuditMapper.Row;
import com.lrj.commerce.runtime.command.persistence.CommandMapper.Key;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.LongFunction;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 平台审计入口，记录尝试与已确认事实，调用方提供领域差异。 */
@Component
public class OperationAudit {
	private static final Logger LOG = LoggerFactory.getLogger(OperationAudit.class);
	private static final String SOURCE = "COMMERCE";
	private static final String PLATFORM = "PLATFORM";
	private static final String TENANT = "TENANT";
	private static final String SECURITY = "SECURITY";
	private static final String UNAVAILABLE = "操作日志暂不可用";
	private static final String INVALID_FILTER = "操作日志筛选条件无效";
	private static final int MAX_PAGE = 100;
	private static final Duration DEFAULT_WINDOW = Duration.ofDays(7);
	private static final Duration MAX_WINDOW = Duration.ofDays(31);
	private static final Pattern SENSITIVE = Pattern.compile(
			"(?iu)bearer\\s|token\\s*[:=]|password\\s*[:=]|cookie\\s*[:=]|口令\\s*[：:]|密码\\s*[：:]"
					+ "|[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}|[0-9][0-9 ()+.-]{8,}[0-9]");

	public enum Action {
		CATEGORY_CREATE("platform.category.create", "BUSINESS"),
		CATEGORY_CHANGE("platform.category.change", "BUSINESS"),
		CATEGORY_DISABLE("platform.category.disable", "BUSINESS"),
		CATEGORY_RENAME("platform.category.rename", "BUSINESS"),
		CATEGORY_REORDER("platform.category.reorder", "BUSINESS"),
		CATEGORY_ENABLE("platform.category.enable", "BUSINESS"),
		CATEGORY_CHANGE_DENIED("platform.category.change", "SECURITY"),
		STORE_OPENING_DRAFT("store.opening.draft", "BUSINESS", true),
		STORE_OPENING_REVISE("store.opening.revise", "BUSINESS", true),
		STORE_OPENING_OPEN("store.opening.open", "BUSINESS", true),
		STORE_OPENING_AUDIT_LIST("store.opening.audit.list", "HIGH_RISK", true),
		CATALOG_BIND("catalog.category.bind", "BUSINESS", true),
		CATALOG_UNBIND("catalog.category.unbind", "BUSINESS", true),
		CATALOG_CHANGE("catalog.category.change", "BUSINESS", true),
		CATALOG_AUDIT_LIST("catalog.category.audit.list", "HIGH_RISK", true),
		ORDER_EVIDENCE_READ("order.evidence.read", "HIGH_RISK", true),
		AUDIT_LIST("platform.audit.list", "HIGH_RISK"),
		AUDIT_READ("platform.audit.read", "HIGH_RISK");

		private final String code;
		private final String policy;
		private final boolean tenantScoped;

		Action(String code, String policy) {
			this(code, policy, false);
		}

		Action(String code, String policy, boolean tenantScoped) {
			this.code = code;
			this.policy = policy;
			this.tenantScoped = tenantScoped;
		}

		/** 稳定编码用于在事务里补充实际生效的动作。 */
		public String code() {
			return code;
		}
	}

	private enum Result { SUCCEEDED, REPLAYED, CONFLICT, DENIED, REJECTED, FAILED, UNKNOWN }

	/** 差异使用动作白名单的字段和类型。 */
	public record Change(String field, Object before, Object after) {
	}

	/** 保存提交前后版本和实际差异，不依赖请求提供的变更快照。 */
	public record Evidence(String resourceId, Long beforeVersion, long afterVersion, String reason,
			List<Change> changes, String actionCode) {
	}

	/** 提交证据和业务返回值由同一个用例计算。 */
	public record Committed<T>(T value, Evidence evidence) {
	}

	public record Item(String id, String sourceSystem, String requestId, String traceId, String action, String actorId,
			String resourceId, Instant occurredAt, String result, String errorCode, String effectId,
			String originalAttemptId, Long beforeVersion, Long afterVersion, String reason, List<Change> changes) {
	}

	public record Page(List<Item> items, String nextCursor) {
	}

	private record Cursor(Instant time, Long id) {
	}

	private final OperationAuditMapper mapper;
	private final TransactionTemplate independent;

	public OperationAudit(OperationAuditMapper mapper, PlatformTransactionManager transactionManager) {
		this.mapper = mapper;
		this.independent = new TransactionTemplate(transactionManager);
		this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.independent.setTimeout(3);
	}

	/** 尝试先持久化，只有业务事务才有权写成功变更。 */
	public <T> T capture(Action action, Actor actor, String resourceId, String traceId, LongFunction<T> operation) {
		return captureForStore(action, actor, null, resourceId, traceId, operation);
	}

	/** 只有资源用例完成门店授权后，才能将目标门店写入范围条件。 */
	public <T> T captureForStore(Action action, Actor actor, String storeId, String resourceId, String traceId,
			LongFunction<T> operation) {
		long id = begin(action, actor, storeId, resourceId, traceId);
		try {
			return operation.apply(id);
		}
		catch (RuntimeException failure) {
			recordFailure(id, failure);
			throw failure;
		}
	}

	/** 平台边界拒绝只保留已认证身份，避免把未授权请求对象记作事实。 */
	public void deniedCategoryChange(Actor actor, String traceId) {
		try {
			long id = begin(Action.CATEGORY_CHANGE_DENIED, actor, null, null, traceId);
			finish(id, Result.DENIED, DomainException.Code.FORBIDDEN.name());
		}
		catch (RuntimeException failure) {
			LOG.error("平台类目拒绝证据写入失败 traceId={} errorType={}", traceId, failure.getClass().getSimpleName());
		}
	}

	/** 由命令在同一事务内以唯一尝试引用提交。 */
	public void effect(Key key, long attemptId, Evidence evidence) {
		String changes = JsonCodec.write(evidence.changes());
		Evidence safe = new Evidence(evidence.resourceId(), evidence.beforeVersion(), evidence.afterVersion(),
				safeText(evidence.reason()), evidence.changes(), evidence.actionCode());
		try {
			checked(mapper.insertEffect(key, attemptId, safe, changes));
		}
		catch (DataAccessException failure) {
			throw unavailable(attemptId, failure);
		}
	}

	/** 重试关联已有命令证据，当前尝试本身不产生新的业务效果。 */
	public void replay(Key key, long attemptId) {
		try {
			checked(mapper.insertReplay(key, attemptId));
		}
		catch (DataAccessException failure) {
			throw unavailable(attemptId, failure);
		}
	}

	/** 限制时间与行数，只读取可信平台范围。 */
	public Page list(Actor actor, String traceId, String from, String to, String actorId, String resourceId,
			String action, String result, String cursor, int limit) {
		requireReader(actor);
		return capture(Action.AUDIT_LIST, actor, null, traceId, id -> {
			Instant end = parseTime(to, Instant.now());
			Instant start = parseTime(from, end.minus(DEFAULT_WINDOW));
			Inputs.require(start.isBefore(end) && Duration.between(start, end).compareTo(MAX_WINDOW) <= 0
					&& limit >= 1 && limit <= MAX_PAGE, INVALID_FILTER);
			if (action != null)
				Inputs.require(Arrays.stream(Action.values()).anyMatch(value -> value.code.equals(action)), INVALID_FILTER);
			if (result != null)
				Inputs.require(Arrays.stream(Result.values()).anyMatch(value -> value.name().equals(result)), INVALID_FILTER);
			Cursor position = parseCursor(cursor, start, end);
			var rows = mapper.list(new Query(null, null, start, end, identifier(actorId, 64), identifier(resourceId, 96), action,
					result, position.time(), position.id(), limit + 1));
			var items = rows.stream().limit(limit).map(OperationAudit::item).toList();
			String next = rows.size() > limit ? items.getLast().occurredAt() + "," + items.getLast().id() : null;
			finish(id, Result.SUCCEEDED, null);
			return new Page(items, next);
		});
	}

	/** 仅由已经验证开店资料权限的用例按单店调用，数据库再次限定可信租户和店铺。 */
	public Page openingHistory(Actor actor, String storeId, String traceId, String cursor) {
		return resourceHistory(Action.STORE_OPENING_AUDIT_LIST, actor, null, storeId, traceId, cursor);
	}

	/** 分类 Owner 权限核验后读取受限门店和分类对象。 */
	public Page catalogHistory(Actor actor, String storeId, String categoryId, String traceId, String cursor) {
		return resourceHistory(Action.CATALOG_AUDIT_LIST, actor, storeId, categoryId, traceId, cursor);
	}

	private Page resourceHistory(Action action, Actor actor, String storeId, String resourceId, String traceId,
			String cursor) {
		return captureForStore(action, actor, storeId, resourceId, traceId, id -> {
			Instant end = Instant.now();
			Instant start = end.minus(MAX_WINDOW);
			Cursor position = parseCursor(cursor, start, end);
			int pageSize = 20;
			var records = mapper.list(new Query(actor.tenantId(), storeId, start, end, null, resourceId, null, null,
					position.time(), position.id(), pageSize + 1));
			var items = records.stream().limit(pageSize).map(OperationAudit::item).toList();
			finish(id, Result.SUCCEEDED, null);
			return new Page(items, records.size() > pageSize ? items.getLast().occurredAt() + "," + items.getLast().id() : null);
		});
	}

	/** 同单查询以调用者已获准的门店为范围，不记录未经来源核验的外部订单号。 */
	public <T> T orderEvidenceRead(Actor actor, String storeId, String traceId, java.util.function.Supplier<T> read) {
		return captureForStore(Action.ORDER_EVIDENCE_READ, actor, storeId, null, traceId, attempt -> {
			T result = read.get();
			finish(attempt, Result.SUCCEEDED, null);
			return result;
		});
	}

	/** 敏感读取必须先写入查询证据，响应才可以返回。 */
	public Item detail(Actor actor, String traceId, long id) {
		requireReader(actor);
		return capture(Action.AUDIT_READ, actor, Long.toString(id), traceId, attempt -> {
			Item result = item(Inputs.found(mapper.detail(id)));
			finish(attempt, Result.SUCCEEDED, null);
			return result;
		});
	}

	/** 自由文本只能进入允许的业务字段，疑似敏感内容使用固定占位。 */
	public static String safeText(String value) {
		return value != null && SENSITIVE.matcher(value).find() ? "已脱敏" : value;
	}

	private long begin(Action action, Actor actor, String storeId, String resourceId, String traceId) {
		String requestId = UUID.randomUUID().toString();
		String scope = action.tenantScoped ? TENANT : actor.role() == Actor.Role.PLATFORM_OPERATOR ? PLATFORM : SECURITY;
		var attempt = new Attempt(SOURCE, requestId, traceId, scope, actor.tenantId(), actor.actorId(), storeId,
				action.code, resourceId, action.policy);
		try {
			return independent.execute(status -> {
				checked(mapper.insertAttempt(attempt));
				return mapper.findAttempt(SOURCE, requestId);
			});
		}
		catch (DataAccessException failure) {
			LOG.error("审计尝试写入失败 traceId={} errorType={}", traceId, failure.getClass().getSimpleName());
			throw new DomainException(DomainException.Code.UNAVAILABLE, UNAVAILABLE);
		}
	}

	private void finish(long id, Result result, String error) {
		try {
			independent.executeWithoutResult(status -> checked(mapper.insertOutcome(id, result.name(), error)));
		}
		catch (DataAccessException failure) {
			throw unavailable(id, failure);
		}
	}

	private static DomainException unavailable(long id, DataAccessException failure) {
		LOG.error("审计证据写入失败 attemptId={} errorType={}", id, failure.getClass().getSimpleName());
		return new DomainException(DomainException.Code.UNAVAILABLE, UNAVAILABLE);
	}

	private void recordFailure(long id, RuntimeException failure) {
		Result result = Result.UNKNOWN;
		String code = null;
		if (failure instanceof DomainException domain) {
			code = domain.code().name();
			result = switch (domain.code()) {
				case FORBIDDEN -> Result.DENIED;
				case CONFLICT, IDEMPOTENCY_CONFLICT, ILLEGAL_TRANSITION -> Result.CONFLICT;
				case INVALID_INPUT, LIMIT_EXCEEDED, SCOPE_MISMATCH, NOT_FOUND -> Result.REJECTED;
				case UNAVAILABLE -> Result.UNKNOWN;
			};
		}
		else if (failure instanceof DataIntegrityViolationException || failure instanceof ConcurrencyFailureException) {
			result = Result.CONFLICT;
			code = DomainException.Code.CONFLICT.name();
		}
		try {
			finish(id, result, code);
		}
		catch (RuntimeException unavailable) {
			// 业务异常仍由原入口处理，未补写的尝试留待按提交证据核实。
			LOG.error("审计结果追加失败 attemptId={} errorType={}", id, unavailable.getClass().getSimpleName());
		}
	}

	private static void requireReader(Actor actor) {
		if (actor == null)
			throw new DomainException(DomainException.Code.FORBIDDEN, "需要平台审计读取权限");
		actor.require(Actor.Capability.OPERATION_AUDIT_READ);
	}

	private static void checked(int count) {
		if (count != 1)
			throw new DomainException(DomainException.Code.UNAVAILABLE, UNAVAILABLE);
	}

	private static Instant parseTime(String value, Instant fallback) {
		if (value == null)
			return fallback;
		try {
			Inputs.require(value.length() <= 30 && value.matches("[1-9][0-9]{3}-.*Z"), INVALID_FILTER);
			return Instant.parse(value);
		}
		catch (DateTimeParseException invalid) {
			throw new DomainException(DomainException.Code.INVALID_INPUT, INVALID_FILTER);
		}
	}

	private static Cursor parseCursor(String cursor, Instant from, Instant to) {
		if (cursor == null)
			return new Cursor(null, null);
		Inputs.require(cursor.length() <= 55, INVALID_FILTER);
		var parts = cursor.split(",", -1);
		Inputs.require(parts.length == 2 && parts[1].matches("[1-9][0-9]{0,18}"), INVALID_FILTER);
		Instant time = parseTime(parts[0], null);
		Inputs.require(!time.isBefore(from) && time.isBefore(to), INVALID_FILTER);
		try {
			return new Cursor(time, Long.parseLong(parts[1]));
		}
		catch (NumberFormatException invalid) {
			throw new DomainException(DomainException.Code.INVALID_INPUT, INVALID_FILTER);
		}
	}

	private static String identifier(String value, int length) {
		Inputs.require(value == null || (!value.isBlank() && value.length() <= length), INVALID_FILTER);
		return value;
	}

	private static Item item(Row row) {
		List<Change> changes = row.changesJson() == null ? List.of()
				: Arrays.asList(JsonCodec.read(row.changesJson(), Change[].class));
		return new Item(Long.toString(row.id()), row.sourceSystem(), row.requestId(), row.traceId(), row.action(),
				row.actorId(), row.resourceId(), row.occurredAt(), row.result(), row.errorCode(),
				ref(row.effectId()), ref(row.originalAttemptId()), row.beforeVersion(), row.afterVersion(),
				row.reason(), changes);
	}

	private static String ref(Long value) {
		return value == null ? null : value.toString();
	}
}
