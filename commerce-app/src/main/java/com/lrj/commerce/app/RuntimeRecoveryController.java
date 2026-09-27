package com.lrj.commerce.app;
import com.lrj.commerce.runtime.*;
import com.lrj.commerce.runtime.api.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 本租户运行时恢复与历史重放：路由层要求租户管理员，用例层再校验运行时能力；租户固定取自凭据，恢复与重放规则、安全门都在运行时服务内。
 * 恢复只接受显式工作标识（每次至多50个），没有“恢复全部”；重放先试运行，再创建受范围、预算与安全门约束的任务。
 */
@RestController @RequestMapping("/v1/admin/runtime")
public class RuntimeRecoveryController {
    private final RuntimeRecovery recovery;private final EventReplay replay;
    public RuntimeRecoveryController(RuntimeRecovery recovery,EventReplay replay){this.recovery=recovery;this.replay=replay;}
    /** 可恢复的工作类型及其允许的动作。 */
    @GetMapping("/work-types") public Object workTypes(@AuthenticationPrincipal Actor actor){return recovery.workTypes(actor);}
    /** 查看：本租户已停止的工作与失败证据，可按失败分类过滤。 */
    @GetMapping("/stopped") public Object stopped(@AuthenticationPrincipal Actor actor,@RequestParam String workType,@RequestParam(required=false) String failureClass,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return recovery.stopped(actor,workType,failureClass,after,limit);}
    /** 执行：对显式标识执行RETRY或SKIP，逐项返回结果并写恢复审计。 */
    @PostMapping("/recoveries") public Object recover(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@RequestBody RuntimeRecovery.Request input){return recovery.recover(actor,key,input);}
    /** 恢复审计历史。 */
    @GetMapping("/recoveries") public Object history(@AuthenticationPrincipal Actor actor,@RequestParam(required=false) String workType,@RequestParam(required=false) String workId,@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="50") int limit){return recovery.history(actor,workType,workId,after,limit);}
    /** 各消费者的副作用分类与两种重放模式的安全门结论。 */
    @GetMapping("/replay/classifications") public Object classifications(@AuthenticationPrincipal Actor actor){return replay.classifications(actor);}
    /** 试运行：只读统计范围内事件、已处理数与安全门结论。 */
    @PostMapping("/replay/dry-run") public Object dryRun(@AuthenticationPrincipal Actor actor,@RequestBody EventReplay.Scope scope){return replay.dryRun(actor,scope);}
    @PostMapping("/replays") public Object create(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@RequestBody EventReplay.Create input){return replay.create(actor,key,input);}
    @GetMapping("/replays") public Object replays(@AuthenticationPrincipal Actor actor,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return replay.list(actor,after,limit);}
    @GetMapping("/replays/{id}") public Object replayJob(@AuthenticationPrincipal Actor actor,@PathVariable String id){return replay.find(actor,id);}
    /** 暂停、恢复或取消重放任务。 */
    @PostMapping("/replays/{id}/control") public Object control(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@RequestBody EventReplay.Control input){return replay.control(actor,key,id,input);}
}
