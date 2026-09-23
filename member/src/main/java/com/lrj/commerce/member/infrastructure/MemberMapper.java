package com.lrj.commerce.member.infrastructure;
import com.lrj.commerce.member.api.MemberApi;
import org.apache.ibatis.annotations.*;
import java.util.List;
/** 仅访问member权威表，所有谓词绑定可信tenant。 */
@Mapper
public interface MemberMapper {
 int change(@Param("tenant") String tenant,@Param("id") String id,@Param("action") String action,@Param("input") MemberApi.Change input);
 void history(@Param("tenant") String tenant,@Param("id") String id,@Param("action") String action,@Param("before") String before,@Param("input") MemberApi.Change input,@Param("actor") String actor);
 List<MemberApi.History> changes(@Param("tenant") String tenant,@Param("id") String id,@Param("after") long after,@Param("limit") int limit);
 void insert(@Param("tenant") String tenant,@Param("input") MemberApi.Create input);
 MemberApi.View find(@Param("tenant") String tenant,@Param("id") String id);
 List<MemberApi.View> list(@Param("tenant") String tenant,@Param("after") String after,@Param("limit") int limit);
 MemberApi.View byActor(@Param("tenant") String tenant,@Param("actor") String actor);
}
