package com.lrj.commerce.app;
import com.lrj.commerce.campaign.api.SegmentApi;
import com.lrj.commerce.runtime.api.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 人群治理与任务控制只向平台运营开放，逐批执行由领域用例限制。 */
@RestController @RequestMapping("/v1/admin")
public class SegmentController {
 private final SegmentApi segments;
 public SegmentController(SegmentApi segments){this.segments=segments;}
 /** 发布定义版本。 */
 @PostMapping("/segments") public Object create(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@RequestBody SegmentApi.Definition input){return segments.create(actor,key,input);}
 /** 最新定义列表。 */
 @GetMapping("/segments") public Object list(@AuthenticationPrincipal Actor actor,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return segments.definitions(actor,after,limit);}
 /** 启停周期刷新。 */
 @PostMapping("/segments/{id}/schedule") public Object schedule(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@RequestBody SegmentApi.Schedule input){return segments.schedule(actor,key,id,input);}
 /** 创建或返回当前刷新任务。 */
 @PostMapping("/segments/{id}/refresh") public Object refresh(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id){return segments.refresh(actor,key,id);}
 /** 刷新记录与检查点。 */
 @GetMapping("/segments/{id}/runs") public Object runs(@AuthenticationPrincipal Actor actor,@PathVariable String id,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return segments.runs(actor,id,after,limit);}
 /** 单次触发严格有界的批处理。 */
 @PostMapping("/segments/pump") public Object pump(@AuthenticationPrincipal Actor actor){return segments.pump(actor);}
 /** 取消和重试不改变定义内容。 */
 @PostMapping("/segment-runs/{id}/{action}") public Object control(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@PathVariable String action){return segments.control(actor,key,id,action);}
}
