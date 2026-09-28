package com.lrj.commerce.app.iam;
import com.lrj.commerce.app.iam.ScopeWork.*;
import java.util.List;
import org.apache.ibatis.annotations.*;
/** IAM消费检查点自己的表，不越界读写门店或商品业务表。 */
@Mapper
public interface ScopeWorkMapper {
    /** 游标上下文持久化，失效不能复用旧after。 */
    int cursor(@Param("c") Cursor cursor);
    /** 按当前本地租户和随机ID读取，过期行不返回。 */
    Cursor cursorById(@Param("tenant") String tenant,@Param("id") String id);
    /** 每租户配额锁串行提交，防止并发绕过任务数量限制。 */
    int quota(@Param("tenant") String tenant);
    /** 只锁本租户短事务配额行，不跨远程授权调用。 */
    String lockQuota(@Param("tenant") String tenant);
    /** 未过期活动任务同时受企业及申请人预算限制。 */
    int active(@Param("tenant") String tenant,@Param("principal") String principal);
    /** 幂等命令事务内插入固定上下文任务头。 */
    int insert(@Param("j") Job job);
    /** 读取自己的任务，过期任务直接拒绝。 */
    Job job(@Param("tenant") String tenant,@Param("id") String id);
    /** 短事务锁当前任务，不在持锁时调用auth。 */
    Job lock(@Param("tenant") String tenant,@Param("id") String id);
    /** 只有SUBMITTED可开始，乐观版本和期限一起检查。 */
    int start(@Param("tenant") String tenant,@Param("id") String id,@Param("version") long version);
    /** 批次提交与行快照原子推进游标/数量/版本。 */
    int advance(@Param("tenant") String tenant,@Param("id") String id,@Param("version") long version,@Param("after") String after,@Param("count") int count,@Param("state") String state);
    /** 同一任务序号唯一，重复工作不能重复导出。 */
    int row(@Param("tenant") String tenant,@Param("job") String job,@Param("r") ExportRow row);
    /** 下载按50行检查点读取，不使用无界结果集。 */
    List<ExportRow> rows(@Param("tenant") String tenant,@Param("job") String job,@Param("after") int after);
}
