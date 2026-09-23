package com.lrj.commerce.member.application;
import com.lrj.commerce.member.api.MemberApi;
import com.lrj.commerce.member.infrastructure.MemberMapper;
import com.lrj.commerce.runtime.Commands;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import java.util.List;

/** Member用例负责权限与状态，SQL仅在本域Mapper。 */
@Service
public class MemberService implements MemberApi {
 private final MemberMapper mapper; private final Commands commands; 
 public MemberService(MemberMapper mapper,Commands commands) {this.mapper=mapper;this.commands=commands;}
 /** 权威主数据只能通过管理用例创建，输入和审计同事务。 */
 public View create(Actor actor,String key,Create input) {
  actor.requireAdmin(); Inputs.require(input!=null,"请求不能为空"); Identifiers.require(input.memberId()); Inputs.text(input.displayName(),128);
  return commands.run(actor,"member.create",key,input,View.class,()->{
   Identifiers.require(input.actorId()); Inputs.text(input.memberLevel(),64);
   mapper.insert(actor.tenantId(),input); return requireActive(actor,input.memberId());
  });
 }
 /** 缺失与非本租户统一拒绝，冻结资源不允许参与新交易。 */
 public View requireActive(Actor actor,String id) {
  Identifiers.require(id); var value=Inputs.found(mapper.find(actor.tenantId(),id));
  if(!value.status().equals("ACTIVE")) throw new DomainException(DomainException.Code.CONFLICT,"资源不可用");
  return value;
 }
 /** 查询同时校验管理权限和分页上限。 */
 public List<View> list(Actor actor,String after,int limit) {actor.requireAdmin();Inputs.page(after,limit);return mapper.list(actor.tenantId(),after,limit);}
 /** 会员身份取自数据库绑定，不相信请求中的memberId。 */ public View current(Actor actor) {return Inputs.found(mapper.byActor(actor.tenantId(),actor.actorId()));}
}
