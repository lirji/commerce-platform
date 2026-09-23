package com.lrj.commerce.member.application;
import com.lrj.commerce.member.api.*;
import com.lrj.commerce.member.infrastructure.GrowthMapper;
import com.lrj.commerce.runtime.Commands;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import java.util.List;

/** 人工标签关联与原因进入命令审计；成员锁限制活跃标签数量并防止丢失更新。 */
@Service
public class MemberTagService implements MemberTagApi {
 private final GrowthMapper mapper;private final Commands commands;
 public MemberTagService(GrowthMapper mapper,Commands commands){this.mapper=mapper;this.commands=commands;}
 /** 标签标识创建后稳定，供规则版本引用。 */
 public Definition create(Actor actor,String key,Definition input){actor.requireAdmin();Inputs.require(input!=null,"标签不能为空");Identifiers.require(input.tagId());Inputs.text(input.name(),64);return commands.run(actor,"member.tag.create",key,input,Definition.class,()->{mapper.tag(actor.tenantId(),input);return input;});}
 /** 字典有界查询。 */
 public List<Definition> definitions(Actor actor,String after,int limit){actor.requireAdmin();Inputs.page(after,limit);return mapper.tagDefinitions(actor.tenantId(),after,limit);}
 /** 原因与赋值同事务，撤销保留关联和版本方便审计。 */
 public Assignment assign(Actor actor,String key,String id,Assign input){
  actor.requireAdmin();Identifiers.require(id);Inputs.require(input!=null&&input.expectedVersion()>=0,"标签版本无效");Identifiers.require(input.tagId());Inputs.text(input.reason(),256);
  return commands.run(actor,"member.tag.assign",key,new Object[]{id,input},Assignment.class,()->{
   var member=Inputs.found(mapper.lockMember(actor.tenantId(),id));Inputs.require(!member.status().equals("CLOSED"),"注销会员不能变更标签");Inputs.found(mapper.tagDefinition(actor.tenantId(),input.tagId()));
   var old=mapper.assignment(actor.tenantId(),id,input.tagId());long version=old==null?0:old.version();if(version!=input.expectedVersion())throw new DomainException(DomainException.Code.CONFLICT,"标签关联版本已变化");
   if(input.active()&&(old==null||!old.active()))Inputs.require(mapper.tags(actor.tenantId(),id).size()<64,"单会员活跃标签最多64个");
   mapper.assign(actor.tenantId(),id,input);return mapper.assignment(actor.tenantId(),id,input.tagId());
  });
 }
 /** 只允许平台管理员读取会员标签关联。 */
 public List<Assignment> assignments(Actor actor,String id,String after,int limit){actor.requireAdmin();Identifiers.require(id);Inputs.page(after,limit);/* 列表不使用requireActive，冻结后仍可审查标签。 */
  return mapper.assignments(actor.tenantId(),id,after,limit);
 }
}
