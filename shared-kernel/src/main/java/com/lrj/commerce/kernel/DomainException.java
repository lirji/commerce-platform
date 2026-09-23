package com.lrj.commerce.kernel;

/** 稳定业务错误与技术异常分开，边界适配器可按 code 转换协议错误。 */
public final class DomainException extends RuntimeException {
    public enum Code { INVALID_INPUT, LIMIT_EXCEEDED, SCOPE_MISMATCH, ILLEGAL_TRANSITION,
        NOT_FOUND, FORBIDDEN, CONFLICT, IDEMPOTENCY_CONFLICT, UNAVAILABLE }
    private final Code code;

    /** 错误说明不得携带敏感业务载荷。 */
    public DomainException(Code code, String message) {
        super(message);
        this.code = code;
    }

    /** 枚举名即稳定协议 code，禁止使用 ordinal。 */
    public Code code() { return code; }
}
