package com.lrj.commerce.app;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.runtime.*;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import static com.lrj.commerce.runtime.FailureClass.*;
import static org.junit.jupiter.api.Assertions.*;

/** 失败分类与重试预算是纯规则：按异常类型层级、SQLState类别与领域错误码判断，不看异常文本；退避可注入随机数确定。 */
class FailureSemanticsTest {

	@Test
	void classifiesByStructuredTypeNotMessage() {
		assertEquals(DEPENDENCY_UNAVAILABLE, of(new org.springframework.jdbc.CannotGetJdbcConnectionException("任意文本")));
		assertEquals(DEPENDENCY_UNAVAILABLE,
				of(new org.springframework.transaction.CannotCreateTransactionException("x")));
		assertEquals(DEPENDENCY_UNAVAILABLE, of(new DomainException(DomainException.Code.UNAVAILABLE, "渠道未配置")));
		assertEquals(CONCURRENCY_RETRYABLE, of(new org.springframework.dao.CannotAcquireLockException("x")));
		assertEquals(CONCURRENCY_RETRYABLE, of(new org.springframework.dao.PessimisticLockingFailureException("x")));
		assertEquals(TRANSIENT, of(new org.springframework.dao.QueryTimeoutException("x")));
		assertEquals(TRANSIENT, of(new org.springframework.transaction.TransactionTimedOutException("x")));
		assertEquals(BUSINESS_REJECTED, of(new DomainException(DomainException.Code.CONFLICT, "x")));
		assertEquals(BUSINESS_REJECTED, of(new DomainException(DomainException.Code.NOT_FOUND, "x")));
		assertEquals(CONFIGURATION_ERROR, of(
				new org.springframework.jdbc.BadSqlGrammarException("t", "select", new SQLSyntaxErrorException("x"))));
		assertEquals(PERMANENT, of(new org.springframework.dao.DuplicateKeyException("x")));
		assertEquals(DATA_CORRUPTION,
				of(tools.jackson.databind.exc.MismatchedInputException.from(null, Integer.class, "x")));
		assertEquals(UNKNOWN, of(new IllegalStateException("数据库连接超时")), "异常文本不参与分类");
		assertEquals(UNKNOWN, of(new NullPointerException()));
	}

	@Test
	void walksCausesAndUsesSqlStateClass() {
		// MyBatis未翻译的异常只能从原因链的JDBC类型或SQLState判断。
		assertEquals(DEPENDENCY_UNAVAILABLE, of(new RuntimeException(new SQLTransientConnectionException("pool"))));
		assertEquals(DEPENDENCY_UNAVAILABLE, of(new RuntimeException(new SQLRecoverableException("link"))));
		assertEquals(CONCURRENCY_RETRYABLE,
				of(new RuntimeException(new SQLTransactionRollbackException("deadlock", "40001"))));
		assertEquals(DEPENDENCY_UNAVAILABLE, of(new RuntimeException(new SQLException("x", "08S01"))));
		assertEquals(CONCURRENCY_RETRYABLE, of(new RuntimeException(new SQLException("x", "40001"))));
		assertEquals(CONFIGURATION_ERROR, of(new RuntimeException(new SQLException("x", "42S22"))));
		assertEquals(UNKNOWN, of(new RuntimeException(new SQLException("x", "HY000"))));
		// 最外层可识别的类型优先：业务拒绝包着的数据库异常仍是业务拒绝。
		var wrapped = new DomainException(DomainException.Code.CONFLICT, "x");
		wrapped.initCause(new SQLTransientConnectionException("pool"));
		assertEquals(BUSINESS_REJECTED, of(wrapped));
		var loop = new RuntimeException("a");
		var inner = new RuntimeException("b", loop);
		loop.initCause(inner);
		assertEquals(UNKNOWN, of(loop), "循环原因链不能死循环");
	}

	@Test
	void onlyDependencyLockAndTimeoutClassesAreTransient() {
		assertEquals(EnumSet.of(TRANSIENT, CONCURRENCY_RETRYABLE, DEPENDENCY_UNAVAILABLE),
				EnumSet.copyOf(Arrays.stream(values()).filter(FailureClass::transientFailure).toList()));
	}

	@Test
	void retryDelaysAreBoundedAndDeterministicUnderInjectedRandomness() {
		var poison = RetryPolicy.POISON;
		assertEquals(List.of(2000L, 4000L, 8000L, 16000L, 16000L),
				java.util.stream.IntStream.rangeClosed(1, 5).mapToObj(n -> poison.delayMillis(n, 0)).toList());
		assertEquals(20000L - 1, poison.delayMillis(4, 0.9999999999), 1, "抖动上限为25%");
		assertFalse(poison.exhausted(4));
		assertTrue(poison.exhausted(5));
		var transientPolicy = RetryPolicy.TRANSIENT;
		assertEquals(256_000L, transientPolicy.delayMillis(8, 0));
		assertEquals(300_000L, transientPolicy.delayMillis(9, 0));
		assertEquals(300_000L, transientPolicy.delayMillis(299, 0));
		assertEquals(360_000L, transientPolicy.delayMillis(300, 0.9999999999), 1, "抖动上限为20%");
		assertTrue(transientPolicy.exhausted(300));
		assertFalse(transientPolicy.exhausted(299));
		// 预算约27小时：前8次指数，之后每次5分钟。
		long total = 0;
		for (int n = 1; n < 300; n++)
			total += transientPolicy.delayMillis(n, 0);
		assertTrue(Duration.ofMillis(total).toHours() >= 24, "瞬时预算至少覆盖一天的依赖故障");
		assertTrue(RetryPolicy.deferralMillis(0) == 32_000 && RetryPolicy.deferralMillis(0.9999999999) <= 38_400);
		assertThrows(IllegalArgumentException.class, () -> poison.delayMillis(0, 0));
		assertThrows(IllegalArgumentException.class, () -> poison.delayMillis(1, 1));
	}

}
