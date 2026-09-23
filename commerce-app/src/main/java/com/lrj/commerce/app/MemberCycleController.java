package com.lrj.commerce.app;

import com.lrj.commerce.member.api.MemberCycleApi;
import com.lrj.commerce.runtime.api.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 周期策略配置与考核入口，贡献事实不向外提供写接口。 */
@RestController @RequestMapping("/v1")
public class MemberCycleController {
    private final MemberCycleApi cycles;
    public MemberCycleController(MemberCycleApi cycles) { this.cycles=cycles; }
    /** 配置只追加版本，运营显式决定启用时间。 */
    @PostMapping("/admin/member-cycles/policies") public Object publish(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@RequestBody MemberCycleApi.Policy input) { return cycles.publish(actor,key,input); }
    /** 策略历史按版本分页。 */
    @GetMapping("/admin/member-cycles/policies") public Object policies(@AuthenticationPrincipal Actor actor,@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="50") int limit) { return cycles.policies(actor,after,limit); }
    /** 显式对账，不依赖页面读取触发任务。 */
    @PostMapping("/admin/member-cycles/{id}/evaluate") public Object evaluate(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id) { return cycles.evaluate(actor,key,id); }
    /** 租户限定的会员考核快照。 */
    @GetMapping("/admin/member-cycles/{id}") public Object read(@AuthenticationPrincipal Actor actor,@PathVariable String id) { actor.requireAdmin();return cycles.read(actor,id); }
    /** 本人快照，不能指定任意会员。 */
    @GetMapping("/members/me/cycle") public Object current(@AuthenticationPrincipal Actor actor) { return cycles.current(actor); }
}
