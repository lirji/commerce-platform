package com.lrj.commerce.member.infrastructure;
import com.lrj.commerce.member.api.MemberApi;
import org.apache.ibatis.annotations.*;
import java.util.List;
/** 仅访问member权威表，所有谓词绑定可信tenant。 */
@Mapper
public interface MemberMapper {
 void insert(@Param("tenant") String tenant,@Param("input") MemberApi.Create input);
 MemberApi.View find(@Param("tenant") String tenant,@Param("id") String id);
 List<MemberApi.View> list(@Param("tenant") String tenant,@Param("after") String after,@Param("limit") int limit);
 MemberApi.View byActor(@Param("tenant") String tenant,@Param("actor") String actor);
}
