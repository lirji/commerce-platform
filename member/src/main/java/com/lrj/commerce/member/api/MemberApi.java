package com.lrj.commerce.member.api;
import com.lrj.commerce.runtime.api.Actor;
import java.util.List;
/** Member边界只暴露不可变业务投影，表由本模块独占。 */
public interface MemberApi {
 record Create(String memberId, String actorId, String displayName, String memberLevel) { }
 record View(String memberId, String actorId, String displayName, String memberLevel, String status, long version) { }
 /** 同一命令重试返回原结果。 */
 View create(Actor actor,String key,Create input);
 /** 从可信租户限定资源，禁止跨租户访问。 */
 View requireActive(Actor actor,String id);
 /** 有界游标分页。 */
 List<View> list(Actor actor,String after,int limit);
 /** 根据认证主体获取本人会员，客户端不能指定其他会员。 */ View current(Actor actor);
}
