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
 private final MemberMapper mapper; private final Commands commands; private final com.lrj.commerce.runtime.Outbox outbox;
 public MemberService(MemberMapper mapper,Commands commands,com.lrj.commerce.runtime.Outbox outbox) {this.mapper=mapper;this.commands=commands;this.outbox=outbox;}
 /** 变更与审计共用事务；乐观锁避免不同运营覆盖彼此决定。 */
 public View change(Actor actor,String key,String id,String action,Change input) {
  actor.requireAdmin(); Identifiers.require(id);
  Inputs.require(input!=null && input.expectedVersion()>=0,"变更版本无效");
  Inputs.text(input.reason(),256); Inputs.text(input.value(),128);
  Inputs.require(java.util.Set.of("PROFILE","STATUS").contains(action),"变更类型无效");
  return commands.run(actor,"member."+action.toLowerCase(),key,new Object[]{id,input},View.class,()->{
   var current=Inputs.found(mapper.find(actor.tenantId(),id));
   if(current.version()!=input.expectedVersion() || current.status().equals("CLOSED"))
    throw new DomainException(DomainException.Code.CONFLICT,"会员版本已变化或已注销，请刷新");
   String before=current.displayName();
   if(action.equals("STATUS")) {
    before=current.status();
    Inputs.require(java.util.Set.of("ACTIVE","FROZEN","CLOSED").contains(input.value()),"状态无效");
    if(before.equals(input.value())) throw new DomainException(DomainException.Code.ILLEGAL_TRANSITION,"会员已处于目标状态");
   }
   if(mapper.change(actor.tenantId(),id,action,input)!=1) throw new DomainException(DomainException.Code.CONFLICT,"会员已被其他操作更新");
   mapper.history(actor.tenantId(),id,action,before,input,actor.actorId());
   return mapper.find(actor.tenantId(),id);
  });
 }
 /** 会员生命周期审计不向普通会员或其他租户暴露。 */
 public List<History> history(Actor actor,String id,long after,int limit) {
  actor.requireAdmin(); Identifiers.require(id); Inputs.require(after>=0,"游标无效"); Inputs.page("",limit);
  Inputs.found(mapper.find(actor.tenantId(),id));return mapper.changes(actor.tenantId(),id,after,limit);
 }
 /** 权威主数据只能通过管理用例创建，输入和审计同事务。 */
 public View create(Actor actor,String key,Create input) {
  actor.requireAdmin(); Inputs.require(input!=null,"请求不能为空"); Identifiers.require(input.memberId()); Inputs.text(input.displayName(),128);
  return commands.run(actor,"member.create",key,input,View.class,()->{
   Identifiers.require(input.actorId()); Inputs.text(input.memberLevel(),64);
   mapper.insert(actor.tenantId(),input);
   outbox.append(actor.tenantId(),"member.registered.v1",input.memberId(),0,new com.lrj.commerce.member.api.MemberGrowthApi.Registered(input.memberId()));
   return requireActive(actor,input.memberId());
  });
 }
 /** 内部锁读不伪装管理员HTTP；调用者必须已有本地事务与目标租户。 */
 @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
 public View lockForOperation(String tenant,String id){Identifiers.require(tenant);Identifiers.require(id);return mapper.lock(tenant,id);}
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
