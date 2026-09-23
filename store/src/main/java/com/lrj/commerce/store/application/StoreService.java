package com.lrj.commerce.store.application;
import com.lrj.commerce.store.api.StoreApi;
import com.lrj.commerce.store.infrastructure.StoreMapper;
import com.lrj.commerce.runtime.Commands;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import java.util.List;
import com.lrj.commerce.merchant.api.MerchantApi;
/** Store用例负责权限与状态，SQL仅在本域Mapper。 */
@Service
public class StoreService implements StoreApi {
 private final StoreMapper mapper; private final Commands commands; private final MerchantApi merchants;
 public StoreService(StoreMapper mapper,Commands commands, MerchantApi merchants) {this.mapper=mapper;this.commands=commands;this.merchants=merchants;}
 /** 权威主数据只能通过管理用例创建，输入和审计同事务。 */
 public View create(Actor actor,String key,Create input) {
  actor.requireAdmin(); Inputs.require(input!=null,"请求不能为空"); Identifiers.require(input.storeId()); Inputs.text(input.name(),128);
  return commands.run(actor,"store.create",key,input,View.class,()->{
   merchants.requireActive(actor,input.merchantId());
   mapper.insert(actor.tenantId(),input); return requireActive(actor,input.storeId());
  });
 }
 /** 缺失与非本租户统一拒绝，冻结资源不允许参与新交易。 */
 public View requireActive(Actor actor,String id) {
  Identifiers.require(id); var value=Inputs.found(mapper.find(actor.tenantId(),id));
  if(!value.status().equals("ACTIVE")) throw new DomainException(DomainException.Code.CONFLICT,"资源不可用");
  merchants.requireActive(actor,value.merchantId()); return value;
 }
 /** 查询同时校验管理权限和分页上限。 */
 public List<View> list(Actor actor,String after,int limit) {actor.requireAdmin();Inputs.page(after,limit);return mapper.list(actor.tenantId(),after,limit);}
 
}
