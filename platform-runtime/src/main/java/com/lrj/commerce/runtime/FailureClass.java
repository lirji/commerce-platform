package com.lrj.commerce.runtime;

import com.lrj.commerce.kernel.DomainException;
import java.sql.*;

/**
 * 后台工作失败分类：只看异常类型层级、JDBC SQLState类别与领域错误码，不匹配异常文本。
 * 瞬时类（依赖不可用、锁冲突、超时）说明工作本身可能健康，不消耗毒工作预算；其余类按有界次数终止并保留证据。
 */
public enum FailureClass {

	TRANSIENT, CONCURRENCY_RETRYABLE, DEPENDENCY_UNAVAILABLE, BUSINESS_REJECTED, DATA_CORRUPTION, CONFIGURATION_ERROR,
	PERMANENT, UNKNOWN;

	/** 数据库或依赖暂时不可用、锁等待与死锁、超时：重试有意义且不代表工作有毒。 */
	public boolean transientFailure() {
		return this == TRANSIENT || this == CONCURRENCY_RETRYABLE || this == DEPENDENCY_UNAVAILABLE;
	}

	/** 由外向内逐层判断，最外层可识别的类型决定分类；都无法识别时为UNKNOWN，按保守有界重试处理。 */
	public static FailureClass of(Throwable failure) {
		var seen = new java.util.HashSet<Throwable>();
		for (Throwable t = failure; t != null && seen.add(t); t = t.getCause()) {
			var c = direct(t);
			if (c != null)
				return c;
		}
		return UNKNOWN;
	}

	private static FailureClass direct(Throwable t) {
		// 领域错误码是稳定协议：UNAVAILABLE表示渠道或依赖暂不可用，其余是业务拒绝。
		if (t instanceof DomainException d)
			return d.code() == DomainException.Code.UNAVAILABLE ? DEPENDENCY_UNAVAILABLE : BUSINESS_REJECTED;
		if (t instanceof tools.jackson.core.JacksonException)
			return DATA_CORRUPTION;
		if (t instanceof org.springframework.dao.PessimisticLockingFailureException
				|| t instanceof org.springframework.dao.ConcurrencyFailureException)
			return CONCURRENCY_RETRYABLE;
		if (t instanceof org.springframework.dao.QueryTimeoutException
				|| t instanceof org.springframework.transaction.TransactionTimedOutException)
			return TRANSIENT;
		if (t instanceof org.springframework.dao.DataAccessResourceFailureException
				|| t instanceof org.springframework.dao.TransientDataAccessResourceException
				|| t instanceof org.springframework.dao.RecoverableDataAccessException
				|| t instanceof org.springframework.transaction.CannotCreateTransactionException)
			return DEPENDENCY_UNAVAILABLE;
		if (t instanceof org.springframework.jdbc.BadSqlGrammarException)
			return CONFIGURATION_ERROR;
		if (t instanceof org.springframework.dao.DataIntegrityViolationException)
			return PERMANENT;
		if (t instanceof SQLException sql)
			return sql(sql);
		if (t instanceof java.io.IOException)
			return DEPENDENCY_UNAVAILABLE;
		if (t instanceof java.util.concurrent.TimeoutException)
			return TRANSIENT;
		return null;
	}

	private static FailureClass sql(SQLException sql) {
		if (sql instanceof SQLTransactionRollbackException)
			return CONCURRENCY_RETRYABLE;
		if (sql instanceof SQLTimeoutException)
			return TRANSIENT;
		if (sql instanceof SQLTransientConnectionException || sql instanceof SQLNonTransientConnectionException
				|| sql instanceof SQLRecoverableException)
			return DEPENDENCY_UNAVAILABLE;
		if (sql instanceof SQLTransientException)
			return TRANSIENT;
		if (sql instanceof SQLSyntaxErrorException)
			return CONFIGURATION_ERROR;
		if (sql instanceof SQLIntegrityConstraintViolationException)
			return PERMANENT;
		if (sql instanceof SQLDataException)
			return DATA_CORRUPTION;
		// SQLState类别：08连接异常，40事务回滚（死锁/序列化），42语法或对象不存在（迁移未执行）。
		String state = sql.getSQLState();
		if (state == null || state.length() < 2)
			return null;
		return switch (state.substring(0, 2)) {
			case "08" -> DEPENDENCY_UNAVAILABLE;
			case "40" -> CONCURRENCY_RETRYABLE;
			case "42" -> CONFIGURATION_ERROR;
			default -> null;
		};
	}

}
