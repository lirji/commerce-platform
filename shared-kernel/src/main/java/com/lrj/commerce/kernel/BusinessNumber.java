package com.lrj.commerce.kernel;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;

/** 商城权威对象的公开编号，内部关联继续使用各应用用例已经持久化的标识。 */
public final class BusinessNumber {
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter BUSINESS_DATE = DateTimeFormatter.ofPattern("uuMMdd", Locale.ROOT)
        .withZone(BUSINESS_ZONE);

    private BusinessNumber() {}

    /** 已登记的商城发号对象。 */
    public enum Type {
        ORDER("ORD"), PAYMENT("PAY"), REFUND("RFD"), AFTERSALE("AFS"), CATALOG_JOB("JOB"), QUOTE("QTE"), SCOPE_EXPORT("EXP");

        private final String prefix;
        private final Pattern pattern;

        Type(String code) {
            this.prefix = "COM-" + code + "-";
            this.pattern = Pattern.compile(prefix + "[0-9]{6}-[0-9]{10}");
        }
    }

    /** 格式化创建时间和已经生成的随机尾段，纯格式契约不依赖随机源。 */
    public static String format(Type type, Instant createdAt, long suffix) {
        return type.prefix + BUSINESS_DATE.format(createdAt) + "-"
            + String.format(Locale.ROOT, "%010d", suffix);
    }

    /** 判断标识对应的查询索引，保留历史记录原号查找。 */
    public static boolean matches(Type type, String value) {
        return value != null && type.pattern.matcher(value).matches();
    }
}
