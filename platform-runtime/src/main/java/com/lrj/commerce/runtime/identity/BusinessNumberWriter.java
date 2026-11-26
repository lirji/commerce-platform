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

    /** 只对已经被占用的展示号重新发号，原幂等键和金额约束错误继续传给调用方。 */
    public static <T> T write(BusinessNumber.Type type, Instant createdAt, Function<String,T> insert,
            Predicate<String> numberTaken) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = next(type, createdAt);
            try {
                return insert.apply(candidate);
            } catch (DuplicateKeyException duplicate) {
                if (attempt == MAX_ATTEMPTS - 1 || !numberTaken.test(candidate)) throw duplicate;
            }
        }
        throw new IllegalStateException("business number allocation exhausted");
    }
}
