package com.lrj.commerce.app;
import com.lrj.commerce.member.api.*;
import com.lrj.commerce.runtime.api.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 会员经营协议只接收运营配置和人工调整，订单成长事实无HTTP写入口。 */
@RestController @RequestMapping("/v1")
public class MemberGrowthController {
 private final MemberGrowthApi growth;private final MemberTagApi tags;
 public MemberGrowthController(MemberGrowthApi growth,MemberTagApi tags){this.growth=growth;this.tags=tags;}
 /** 发布不可变成长策略。 */
 @PostMapping("/admin/member-growth/policies") public Object policy(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@RequestBody MemberGrowthApi.Policy input){return growth.publish(actor,key,input);}
 /** 策略历史游标查询。 */
 @GetMapping("/admin/member-growth/policies") public Object policies(@AuthenticationPrincipal Actor actor,@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="50") int limit){return growth.policies(actor,after,limit);}
 /** 平台运营查看会员成长。 */
 @GetMapping("/admin/member-growth/{id}") public Object wallet(@AuthenticationPrincipal Actor actor,@PathVariable String id){return growth.wallet(actor,id);}
 /** 不可变账本。 */
 @GetMapping("/admin/member-growth/{id}/ledger") public Object ledger(@AuthenticationPrincipal Actor actor,@PathVariable String id,@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="50") int limit){return growth.ledger(actor,id,after,limit);}
 /** 人工校准必须有原因和预期版本。 */
 @PostMapping("/admin/member-growth/{id}/adjust") public Object adjust(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@RequestBody MemberGrowthApi.Adjustment input){return growth.adjust(actor,key,id,input);}
 /** 明确触发等级重算，不在读取时偷偷写入。 */
 @PostMapping("/admin/member-growth/{id}/recalculate") public Object recalculate(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id){return growth.recalculate(actor,key,id);}
 /** 会员只读本人档案。 */
 @GetMapping("/members/me/growth") public Object current(@AuthenticationPrincipal Actor actor){return growth.current(actor);}
 /** 会员账本主体由认证绑定。 */
 @GetMapping("/members/me/growth/ledger") public Object currentLedger(@AuthenticationPrincipal Actor actor,@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="50") int limit){return growth.ledger(actor,growth.current(actor).memberId(),after,limit);}
 /** 创建稳定标签字典。 */
 @PostMapping("/admin/member-tags") public Object tag(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@RequestBody MemberTagApi.Definition input){return tags.create(actor,key,input);}
 /** 字典列表。 */
 @GetMapping("/admin/member-tags") public Object tags(@AuthenticationPrincipal Actor actor,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return tags.definitions(actor,after,limit);}
 /** 标签赋值与撤销。 */
 @PostMapping("/admin/member-tags/{id}/assign") public Object assign(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@RequestBody MemberTagApi.Assign input){return tags.assign(actor,key,id,input);}
 /** 关联含版本，客户端据此执行下一次修改。 */
 @GetMapping("/admin/member-tags/{id}/assignments") public Object assignments(@AuthenticationPrincipal Actor actor,@PathVariable String id,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return tags.assignments(actor,id,after,limit);}
}
