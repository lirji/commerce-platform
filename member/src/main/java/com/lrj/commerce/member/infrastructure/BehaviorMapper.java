package com.lrj.commerce.member.infrastructure;
import com.lrj.commerce.member.api.MemberBehaviorApi.*;
import org.apache.ibatis.annotations.*;
import java.time.*;
import java.util.List;
/** 行为查询限定租户和批次，日汇总避免扫描全部原始事件。 */
@Mapper
public interface BehaviorMapper {
    record Row(String memberId,int browse30,int cart30,long completedOrders30,String netSpend30,Instant lastOrderAt,Instant lastCartAt,Instant joinedAt,String birthday,boolean journeyEnabled) { }
    record OrderSource(String memberId,boolean completed,String netSpend) { }
    Profile profile(@Param("tenant") String tenant,@Param("member") String member);
    void ensure(@Param("tenant") String tenant,@Param("member") String member);
    int profileChange(@Param("tenant") String tenant,@Param("member") String member,@Param("input") ProfileChange input);
    Event event(@Param("tenant") String tenant,@Param("member") String member,@Param("id") String id);
    void eventInsert(@Param("tenant") String tenant,@Param("member") String member,@Param("input") Signal input,@Param("at") Instant at);
    List<Event> events(@Param("tenant") String tenant,@Param("member") String member,@Param("after") long after,@Param("limit") int limit);
    Integer dayCount(@Param("tenant") String tenant,@Param("member") String member,@Param("day") LocalDate day);
    void dayAdd(@Param("tenant") String tenant,@Param("member") String member,@Param("day") LocalDate day,@Param("kind") Kind kind,@Param("at") Instant at);
    String sourceMember(@Param("tenant") String tenant,@Param("order") String order);
    OrderSource source(@Param("tenant") String tenant,@Param("order") String order);
    void project(@Param("tenant") String tenant,@Param("order") String order,@Param("input") OrderSource input,@Param("at") Instant at);
    List<Row> facts(@Param("tenant") String tenant,@Param("ids") List<String> ids,@Param("startDay") LocalDate startDay,@Param("endDay") LocalDate endDay,@Param("start") Instant start,@Param("now") Instant now);
}
