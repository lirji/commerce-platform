package com.lrj.commerce.campaign.infrastructure;
import com.lrj.commerce.campaign.api.*;
import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;

/** 分群仅写营销投影，不跨域访问会员表；输入来自会员API。 */
@Mapper
public interface SegmentMapper {
 List<String> announcements(@Param("tenant") String tenant,@Param("now") Instant now);
 void announcementFailed(@Param("tenant") String tenant,@Param("id") String id,@Param("due") Instant due);
 int retryAnnouncement(@Param("tenant") String tenant,@Param("id") String id,@Param("now") Instant now);
 List<String> entered(@Param("tenant") String tenant,@Param("run") SegmentApi.Run run);
 void announced(@Param("tenant") String tenant,@Param("id") String id,@Param("cursor") String cursor,@Param("done") boolean done);
 record Root(String segmentId,String audienceId,long currentVersion,long snapshotSequence,boolean enabled,Instant nextDue,long lockVersion) { }
 record Row(String definitionJson,String audienceId,boolean enabled,long lockVersion) { }
 Root lockRoot(@Param("tenant") String tenant,@Param("id") String id);
 void insertRoot(@Param("tenant") String tenant,@Param("id") String id,@Param("audience") String audience,@Param("version") long version,@Param("now") Instant now);
 void advanceDefinition(@Param("tenant") String tenant,@Param("id") String id,@Param("version") long version,@Param("now") Instant now);
 void definition(@Param("tenant") String tenant,@Param("input") SegmentApi.Definition input,@Param("json") String json);
 Row find(@Param("tenant") String tenant,@Param("id") String id);
 String definitionJson(@Param("tenant") String tenant,@Param("id") String id,@Param("version") long version);
 List<Row> definitions(@Param("tenant") String tenant,@Param("after") String after,@Param("limit") int limit);
 int schedule(@Param("tenant") String tenant,@Param("id") String id,@Param("input") SegmentApi.Schedule input,@Param("now") Instant now);
 void allocate(@Param("tenant") String tenant,@Param("id") String id,@Param("due") Instant due);
 void run(@Param("tenant") String tenant,@Param("run") SegmentApi.Run run);
 SegmentApi.Run runFind(@Param("tenant") String tenant,@Param("id") String id);
 SegmentApi.Run lockRun(@Param("tenant") String tenant,@Param("id") String id);
 SegmentApi.Run active(@Param("tenant") String tenant,@Param("id") String id);
 List<SegmentApi.Run> runs(@Param("tenant") String tenant,@Param("id") String id,@Param("after") String after,@Param("limit") int limit);
 List<String> due(@Param("tenant") String tenant,@Param("now") Instant now);
 List<String> pending(@Param("tenant") String tenant,@Param("now") Instant now);
 List<String> tenants(@Param("after") String after,@Param("now") Instant now);
 void matched(@Param("tenant") String tenant,@Param("run") SegmentApi.Run run,@Param("members") List<String> members);
 void checkpoint(@Param("tenant") String tenant,@Param("id") String id,@Param("cursor") String cursor,@Param("processed") int processed,@Param("matched") int matched);
 void publish(@Param("tenant") String tenant,@Param("run") SegmentApi.Run run,@Param("name") String name,@Param("matched") int matched);
 void status(@Param("tenant") String tenant,@Param("id") String id,@Param("status") String status,@Param("error") String error);
 void failed(@Param("tenant") String tenant,@Param("id") String id,@Param("due") Instant due);
}
