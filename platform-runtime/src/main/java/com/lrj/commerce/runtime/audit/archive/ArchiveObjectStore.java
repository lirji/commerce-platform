package com.lrj.commerce.runtime.audit.archive;

import java.util.List;

/** 档案对象的写入和读取可使用独立凭据；始终按已保存的确切版本校验。 */
public interface ArchiveObjectStore {
    String putOnce(String key, byte[] bytes, String sha256);

    byte[] readForVerification(String key, String version, int exactBytes);

    byte[] readForRestore(String key, String version, int exactBytes);

    /** 只供恢复清点检查原外部记录，错误不能当作记录不存在。 */
    InventoryPage inventory(String prefix, String continuation, int limit);

    ObjectVersion currentVersion(String key);

    record InventoryPage(List<String> keys, String continuation) {}

    record ObjectVersion(String version, int byteCount, String sha256) {}
}
