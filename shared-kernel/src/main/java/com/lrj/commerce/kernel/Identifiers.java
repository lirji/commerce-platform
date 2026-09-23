package com.lrj.commerce.kernel;

/** 不规范化标识，避免不同外部标识被静默合并。 */
public final class Identifiers {
    private Identifiers() { }

    /** 跨域只传稳定标识值，不传持久化实体。 */
    public static String require(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,64}")) {
            throw new DomainException(DomainException.Code.INVALID_INPUT, "标识格式无效");
        }
        return value;
    }
}
