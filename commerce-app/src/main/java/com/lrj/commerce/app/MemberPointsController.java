package com.lrj.commerce.app;
import com.lrj.commerce.member.api.MemberPointsApi;
import com.lrj.commerce.runtime.api.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 积分经营入口只开放策略和有审计的校准，奖励事实由内部消费者提供。 */
@RestController @RequestMapping("/v1")
public class MemberPointsController {
    private final MemberPointsApi points;
    public MemberPointsController(MemberPointsApi points){this.points=points;}
    /** 发布不可变获取/消费规则。 */
    @PostMapping("/admin/member-points/policies") public Object publish(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@RequestBody MemberPointsApi.Policy input){return points.publish(actor,key,input);}
    /** 策略历史分页。 */
    @GetMapping("/admin/member-points/policies") public Object policies(@AuthenticationPrincipal Actor actor,@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="50") int limit){return points.policies(actor,after,limit);}
    /** 管理员查看本租户钱包。 */
    @GetMapping("/admin/member-points/{id}") public Object wallet(@AuthenticationPrincipal Actor actor,@PathVariable String id){actor.requireAdmin();return points.wallet(actor,id);}
    /** 管理账本不返回认证机密或订单地址。 */
    @GetMapping("/admin/member-points/{id}/ledger") public Object ledger(@AuthenticationPrincipal Actor actor,@PathVariable String id,@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="50") int limit){actor.requireAdmin();return points.ledger(actor,id,after,limit);}
    /** 人工调整必须有原因与预期版本。 */
    @PostMapping("/admin/member-points/{id}/adjust") public Object adjust(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@RequestBody MemberPointsApi.Adjustment input){return points.adjust(actor,key,id,input);}
    /** 显式推进到期批次，不以查询替代运营命令。 */
    @PostMapping("/admin/member-points/{id}/expire") public Object expire(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id){return points.expire(actor,key,id);}
    /** 会员身份由认证绑定。 */
    @GetMapping("/members/me/points") public Object current(@AuthenticationPrincipal Actor actor){return points.current(actor);}
    /** 本人不可变账本。 */
    @GetMapping("/members/me/points/ledger") public Object currentLedger(@AuthenticationPrincipal Actor actor,@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="50") int limit){return points.ledger(actor,points.current(actor).memberId(),after,limit);}
}
