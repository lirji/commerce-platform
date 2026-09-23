package com.lrj.commerce.merchant.application;
import com.lrj.commerce.merchant.api.MerchantApi;
import com.lrj.commerce.merchant.infrastructure.MerchantMapper;
import com.lrj.commerce.runtime.Commands;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import java.util.List;

/** Merchant用例负责权限与状态，SQL仅在本域Mapper。 */
@Service
public class MerchantService implements MerchantApi {
 private final MerchantMapper mapper; private final Commands commands; 
 public MerchantService(MerchantMapper mapper,Commands commands) {this.mapper=mapper;this.commands=commands;}
 /** 权威主数据只能通过管理用例创建，输入和审计同事务。 */
 public View create(Actor actor,String key,Create input) {
  actor.requireAdmin(); Inputs.require(input!=null,"请求不能为空"); Identifiers.require(input.merchantId()); Inputs.text(input.name(),128);
  return commands.run(actor,"merchant.create",key,input,View.class,()->{
   
   mapper.insert(actor.tenantId(),input); return requireActive(actor,input.merchantId());
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
 
}
