package com.lrj.commerce.runtime.identity;

import com.lrj.commerce.kernel.BusinessNumber;
import java.time.Instant;
import java.util.function.Function;
import java.util.function.Predicate;
import org.springframework.dao.DuplicateKeyException;

/** 订单关联在各业务模块原事务内写入，这里只处理极少数全局编号冲突。 */
public final class BusinessNumberWriter {
    private static final int MAX_ATTEMPTS = 4;

    private BusinessNumberWriter() {}

    /** 只对已经被占用的展示号重新发号，原幂等键和金额约束错误继续传给调用方。 */
    public static <T> T write(BusinessNumber.Type type, Instant createdAt, Function<String,T> insert,
            Predicate<String> numberTaken) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = BusinessNumber.next(type, createdAt);
            try {
                return insert.apply(candidate);
            } catch (DuplicateKeyException duplicate) {
                if (attempt == MAX_ATTEMPTS - 1 || !numberTaken.test(candidate)) throw duplicate;
            }
        }
        throw new IllegalStateException("business number allocation exhausted");
    }
}
