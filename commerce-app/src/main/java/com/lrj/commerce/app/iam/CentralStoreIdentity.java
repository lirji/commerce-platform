package com.lrj.commerce.app.iam;

import com.lrj.commerce.runtime.api.identity.Actor;

/** 仅本次门店读取请求持有用户证据，不能当成通用业务Actor或可复用授权票据。 */
public record CentralStoreIdentity(String userToken, String authTenant, long generation, Actor actor) {
    /** 日志/错误不得通过record默认输出泄漏Bearer证据。 */
    @Override public String toString() { return "CentralStoreIdentity[credentials=redacted]"; }
}
