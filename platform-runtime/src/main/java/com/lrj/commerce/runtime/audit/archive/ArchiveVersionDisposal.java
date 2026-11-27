package com.lrj.commerce.runtime.audit.archive;

/** 归档到期使用独立身份操作精确版本。存储拒绝、超时和已验证不存在分别处理。 */
public interface ArchiveVersionDisposal {
    boolean exists(String key, String version);

    void delete(String key, String version);
}
