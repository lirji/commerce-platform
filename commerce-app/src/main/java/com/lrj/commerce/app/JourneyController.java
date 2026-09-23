package com.lrj.commerce.app;
import com.lrj.commerce.journey.api.JourneyApi;
import com.lrj.commerce.runtime.api.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
/** HTTP仅转换协议参数，审批、身份和恢复约束由旅程用例校验。 */
@RestController @RequestMapping("/v1")
public class JourneyController {
    private final JourneyApi journeys;
    public JourneyController(JourneyApi journeys){this.journeys=journeys;}
    record Revision(long expectedVersion) { }
    /** 不可变版本草稿。 */
    @PostMapping("/admin/journeys") public Object create(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@RequestBody JourneyApi.Definition input){return journeys.create(actor,key,input);}
    /** 有界读取最新定义。 */
    @GetMapping("/admin/journeys") public Object list(@AuthenticationPrincipal Actor actor,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return journeys.definitions(actor,after,limit);}
    /** 明确动作和预期版本，不能任意指定新状态。 */
    @PostMapping("/admin/journeys/{id}/{version}/{action}") public Object change(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@PathVariable long version,@PathVariable String action,@RequestBody Revision input){return journeys.change(actor,key,id,version,input.expectedVersion(),action);}
    /** 手工入组保留来源去重键。 */
    @PostMapping("/admin/journey-instances") public Object enroll(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@RequestBody JourneyApi.Start input){return journeys.enroll(actor,key,input);}
    /** 查询范围由服务端认证身份决定。 */
    @GetMapping({"/admin/journey-instances","/journey-instances"}) public Object instances(@AuthenticationPrincipal Actor actor,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return journeys.instances(actor,after,limit);}
    /** 取消或重试均写命令审计。 */
    @PostMapping("/admin/journey-instances/{id}/{action}") public Object control(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@PathVariable String action){return journeys.control(actor,key,id,action);}
    /** 运维只推动有限批次。 */
    @PostMapping("/admin/journeys/pump") public Object pump(@AuthenticationPrincipal Actor actor){return journeys.pump(actor);}
    /** 持久扫描的当前进度与失败状态。 */
    @GetMapping("/admin/journey-scans") public Object scans(@AuthenticationPrincipal Actor actor,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return journeys.scans(actor,after,limit);}
    /** 隔离恢复保留原检查点，并记录原因。 */
    @PostMapping("/admin/journey-scans/{id}/{version}/retry") public Object retryScan(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@PathVariable long version,@RequestBody JourneyApi.ScanRetry input){return journeys.retryScan(actor,key,id,version,input);}
    /** 读取本人的真实触达记录。 */
    @GetMapping("/notifications") public Object notifications(@AuthenticationPrincipal Actor actor,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return journeys.notifications(actor,after,limit);}
}
