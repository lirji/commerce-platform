package com.lrj.commerce.app;

import com.lrj.commerce.member.api.MemberApi;
import com.lrj.commerce.runtime.api.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 会员经营协议边界；身份、终态与版本约束在会员用例中再次校验。 */
@RestController @RequestMapping("/v1/admin/members")
public class MemberOperationsController {
 private final MemberApi members;
 public MemberOperationsController(MemberApi members){this.members=members;}
 /** 更新显示资料，认证绑定不可随意换绑。 */
 @PostMapping("/{id}/profile") public Object profile(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@RequestBody MemberApi.Change input){return members.change(actor,key,id,"PROFILE",input);}
 /** 生命周期变更必须填写原因。 */
 @PostMapping("/{id}/status") public Object status(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@RequestBody MemberApi.Change input){return members.change(actor,key,id,"STATUS",input);}
 /** 有界审计查询。 */
 @GetMapping("/{id}/history") public Object history(@AuthenticationPrincipal Actor actor,@PathVariable String id,@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="50") int limit){return members.history(actor,id,after,limit);}
}
