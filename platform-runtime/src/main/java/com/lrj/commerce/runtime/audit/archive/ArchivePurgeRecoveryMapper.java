package com.lrj.commerce.runtime.audit.archive;

import com.lrj.commerce.runtime.audit.archive.ArchiveOnlineCutoverMapper.Receipt;
import com.lrj.commerce.runtime.audit.archive.AuditArchiveMapper.Scope;
import com.lrj.commerce.runtime.audit.archive.AuditArchivePurgeRecovery.MemberProof;
import com.lrj.commerce.runtime.audit.archive.AuditArchivePurgeRecovery.Reference;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 恢复凭证与在线清理回执共用业务主键，补放只访问凭证指定的行。 */
@Mapper
public interface ArchivePurgeRecoveryMapper {
    String nextPending(@Param("scope") Scope scope);

    Receipt receipt(@Param("id") String id);

    Reference reference(@Param("id") String id);

    int sealReference(Reference reference);

    /** 也锁定意外出现在其他租户的同流水行，给用例提供完整的冲突视图。 */
    List<Long> lockExistingAttempts(@Param("ids") List<Long> ids);

    List<Long> memberIds(@Param("id") String id);

    boolean hasOnlineChildren(@Param("id") String id);

    int storeRecoveredLocators(@Param("id") String id, @Param("effects") List<MemberProof> effects);

    int markRecovered(@Param("id") String id, @Param("version") String version);
}
