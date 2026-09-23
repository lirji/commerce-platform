package com.lrj.commerce.member.api;
import com.lrj.commerce.runtime.api.Actor;
import java.util.List;
/** Member边界只暴露不可变业务投影，表由本模块独占。 */
public interface MemberApi {
 record Stats(long total,long active,long frozen,long closed) { }
 /** 当前租户会员状态分布，不从分页列表推算总数。 */
 Stats stats(Actor actor);
 record Create(String memberId, String actorId, String displayName, String memberLevel) { }
 record View(String memberId, String actorId, String displayName, String memberLevel, String status, long version) { }
 record Change(long expectedVersion,String value,String reason) { }
 record History(long version,String action,String beforeValue,String afterValue,String reason,String actorId,java.time.Instant createdAt) { }
 /** 资料和状态分别建模，注销为不可恢复终态。 */
 View change(Actor actor,String key,String memberId,String action,Change input);
 /** 只读变更记录，按版本稳定分页。 */
 List<History> history(Actor actor,String memberId,long after,int limit);
 /** 内部事务当前锁读，供批次发放与冻结状态变更串行协作；缺失返回null。 */
 View lockForOperation(String tenant,String id);
 /** 同一命令重试返回原结果。 */
 View create(Actor actor,String key,Create input);
 /** 从可信租户限定资源，禁止跨租户访问。 */
 View requireActive(Actor actor,String id);
 /** 有界游标分页。 */
 List<View> list(Actor actor,String after,int limit);
 /** 根据认证主体获取本人会员，客户端不能指定其他会员。 */ View current(Actor actor);
}
