package com.lrj.commerce.runtime.audit.archive;

/** 档案对象的写入和读取可使用独立凭据；始终按已保存的确切版本校验。 */
public interface ArchiveObjectStore {
    String putOnce(String key, byte[] bytes, String sha256);

    byte[] readForVerification(String key, String version, int exactBytes);

    byte[] readForRestore(String key, String version, int exactBytes);
}
