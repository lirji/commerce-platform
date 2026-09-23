package com.lrj.commerce.campaign.api;
import com.lrj.commerce.runtime.api.Actor;
import java.time.Instant;
import java.util.List;

/** 人群规则版本和输出快照版本分离，长任务只发布完整结果。 */
public interface SegmentApi {
 record Definition(String segmentId,long version,String name,RuleNode rule,int ttlSeconds,int refreshSeconds,int maxMembers) { }
 record View(Definition content,String audienceId,boolean enabled,long lockVersion) { }
 record Schedule(long expectedVersion,boolean enabled) { }
 record Entered(String memberId,String segmentId,String audienceId,long snapshotVersion,long definitionVersion) { }
 record Run(String runId,String segmentId,long definitionVersion,String audienceId,long snapshotVersion,String cursorMember,int processed,int matched,String status,int attempts,String errorCode,Instant startedAt,Instant validUntil,Instant availableAt,boolean entriesAnnounced,int entryAttempts) { }
 View create(Actor actor,String key,Definition input);
 List<View> definitions(Actor actor,String after,int limit);
 View schedule(Actor actor,String key,String id,Schedule input);
 Run refresh(Actor actor,String key,String id);
 List<Run> runs(Actor actor,String id,String after,int limit);
 Run control(Actor actor,String key,String id,String action);
 int pump(Actor actor);
 int tick();
}
