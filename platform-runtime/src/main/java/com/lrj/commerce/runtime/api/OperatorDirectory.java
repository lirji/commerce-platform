package com.lrj.commerce.runtime.api;

/** 身份目录只暴露主体资格，不将凭据摘要暴露给业务模块。 */
public interface OperatorDirectory {
 boolean active(String tenantId,String actorId);
}
