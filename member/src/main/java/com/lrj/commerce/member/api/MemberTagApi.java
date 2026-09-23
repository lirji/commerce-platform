package com.lrj.commerce.member.api;
import com.lrj.commerce.runtime.api.Actor;
import java.util.List;

/** 标签只从已治理字典关联到本租户会员，供可信规则事实使用。 */
public interface MemberTagApi {
 record Definition(String tagId,String name) { }
 record Assign(String tagId,boolean active,long expectedVersion,String reason) { }
 record Assignment(String tagId,boolean active,long version,String source,String reason) { }
 Definition create(Actor actor,String key,Definition input);
 List<Definition> definitions(Actor actor,String after,int limit);
 Assignment assign(Actor actor,String key,String memberId,Assign input);
 List<Assignment> assignments(Actor actor,String memberId,String after,int limit);
}
