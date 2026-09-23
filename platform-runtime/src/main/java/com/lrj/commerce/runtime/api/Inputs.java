package com.lrj.commerce.runtime.api;

import com.lrj.commerce.kernel.DomainException;

/** 边界输入校验集中为小型值检查，不承载跨域业务规则。 */
public final class Inputs {
    private Inputs() { }
    public static String text(String value, int maximum) {
        require(value != null && !value.isBlank() && value.length() <= maximum, "文本字段无效");
        return value;
    }
    public static void require(boolean condition, String message) {
        if (!condition) throw new DomainException(DomainException.Code.INVALID_INPUT, message);
    }
    public static void page(String after, int limit) {
        require(after != null && after.length() <= 64 && limit >= 1 && limit <= 100, "分页参数无效");
    }
    public static <T> T found(T value) {
        if (value == null) throw new DomainException(DomainException.Code.NOT_FOUND, "资源不存在或不属于当前身份");
        return value;
    }
}
