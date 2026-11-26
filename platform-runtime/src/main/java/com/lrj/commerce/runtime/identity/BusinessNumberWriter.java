package com.lrj.commerce.runtime.identity;

import com.lrj.commerce.kernel.BusinessNumber;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.function.Function;
import java.util.function.Predicate;
import org.springframework.dao.DuplicateKeyException;

/** 订单关联在各业务模块原事务内写入，这里只处理极少数全局编号冲突。 */
public final class BusinessNumberWriter {
    private static final int MAX_ATTEMPTS = 4;
    private static final long SUFFIX_SPACE = 10_000_000_000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private BusinessNumberWriter() {}

    /** 发号随机源属于运行时，编号协议仅提供日期与格式。 */
    public static String next(BusinessNumber.Type type, Instant createdAt) {
        return BusinessNumber.format(type, createdAt, RANDOM.nextLong(SUFFIX_SPACE));
    }

    /** 唯一索引明确时按冲突索引重试，避免为发号跨租户查询。 */
    public static <T> T writeOnIndex(BusinessNumber.Type type, Instant createdAt, Function<String,T> insert,
            String index) {
        return write(type, createdAt, insert, (candidate, failure) -> {
            String message = failure.getMostSpecificCause().getMessage();
            return message != null && (message.endsWith("." + index + "'") || message.endsWith("'" + index + "'"));
        });
    }

    /** 只对已经被占用的展示号重新发号，原幂等键和金额约束错误继续传给调用方。 */
    public static <T> T write(BusinessNumber.Type type, Instant createdAt, Function<String,T> insert,
            Predicate<String> numberTaken) {
        return write(type, createdAt, insert, (candidate, failure) -> numberTaken.test(candidate));
    }

    private static <T> T write(BusinessNumber.Type type, Instant createdAt, Function<String,T> insert,
            java.util.function.BiPredicate<String,DuplicateKeyException> collision) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = next(type, createdAt);
            try {
                return insert.apply(candidate);
            } catch (DuplicateKeyException duplicate) {
                if (attempt == MAX_ATTEMPTS - 1 || !collision.test(candidate, duplicate)) throw duplicate;
            }
        }
        throw new IllegalStateException("business number allocation exhausted");
    }
}
