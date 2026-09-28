package com.lrj.commerce.app.iam;
import com.lrj.commerce.runtime.api.scope.ScopeQuery;
import java.time.Instant;
import java.util.List;
/** 仅保存资源结果与上下文摘要，不保存用户Token或可复用ALLOW。 */
public final class ScopeWork {
    private ScopeWork() {}
    /** 任务状态只通过带版本条件的合法迁移推进。 */
    public enum State {
        SUBMITTED("SUBMITTED"),RUNNING("RUNNING"),COMPLETED("COMPLETED");
        private final String code;State(String code){this.code=code;}
        /** 稳定持久化编码。 */ public String code(){return code;}
    }
    /** 不向浏览器暴露授权摘要；游标只返回随机ID。 */
    public record Cursor(String id,String tenantId,String principalId,String binding,String resourceType,String search,String afterId,Instant expiresAt) {}
    /** 任务头与扫描进度同事务，重试不得覆盖新检查点。 */
    public record Job(String id,String tenantId,String principalId,String membershipId,long generation,String binding,String resourceType,String search,
                      String state,String afterId,int rowCount,long version,Instant expiresAt) {}
    /** 对外状态去掉内部绑定摘要及主体ID。 */
    public record JobView(String id,String resourceType,String state,int rowCount,long version,String expiresAt) {}
    /** 分页、count、统计都来自同一授权谓词。 */
    public record Page(List<ScopeQuery.Row> items,long total,long stores,String nextCursor) {}
    /** 下载返回有界已验证快照，不生成公开永久URL。 */
    public record Download(String jobId,String resourceType,List<ScopeQuery.Row> rows) {}
    /** 持久化序号稳定，批次提交与行插入原子完成。 */
    public record ExportRow(int sequence,String resourceId,long resourceVersion,String snapshotJson) {}
}
