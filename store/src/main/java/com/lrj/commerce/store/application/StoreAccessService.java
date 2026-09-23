package com.lrj.commerce.store.application;
import com.lrj.commerce.store.api.*;
import com.lrj.commerce.store.infrastructure.StoreAccessMapper;
import com.lrj.commerce.merchant.api.MerchantApi;
import com.lrj.commerce.runtime.*;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import java.util.List;

/** 平台管理员授予资源范围，运营主体没有转授或读取全租户数据的权力。 */
@Service
public class StoreAccessService implements StoreAccessApi {
 private final StoreAccessMapper mapper;private final StoreApi stores;private final MerchantApi merchants;private final OperatorDirectory identities;private final Commands commands;
 public StoreAccessService(StoreAccessMapper mapper,StoreApi stores,MerchantApi merchants,OperatorDirectory identities,Commands commands){this.mapper=mapper;this.stores=stores;this.merchants=merchants;this.identities=identities;this.commands=commands;}
 /** 商家范围包含后续新增店铺，选择时必须显式声明资源类型。 */
 public Grant create(Actor actor,String key,Create input){
  actor.requireAdmin();Inputs.require(input!=null,"授权不能为空");Identifiers.require(input.grantId());Identifiers.require(input.actorId());Identifiers.require(input.resourceId());Inputs.text(input.reason(),256);
  Inputs.require("CATALOG".equals(input.permission()) && ("STORE".equals(input.resourceType())||"MERCHANT".equals(input.resourceType())),"授权类型无效");
  return commands.run(actor,"store.grant",key,input,Grant.class,()->{
   Inputs.require(identities.active(actor.tenantId(),input.actorId()),"需要本租户有效运营身份");
   if(input.resourceType().equals("STORE"))stores.requireActive(actor,input.resourceId());else merchants.requireActive(actor,input.resourceId());
   mapper.insert(actor.tenantId(),input);return mapper.find(actor.tenantId(),input.grantId());
  });
 }
 /** 撤销直接修改权威库，不依赖缓存失效通知。 */
 public Grant change(Actor actor,String key,String id,Change input){
  actor.requireAdmin();Identifiers.require(id);Inputs.require(input!=null&&input.expectedVersion()>=0,"版本无效");Inputs.text(input.reason(),256);
  return commands.run(actor,"store.grant.status",key,new Object[]{id,input},Grant.class,()->{
   var old=Inputs.found(mapper.find(actor.tenantId(),id));
   if(input.active())Inputs.require(identities.active(actor.tenantId(),old.actorId()),"运营身份已失效");
   if(mapper.change(actor.tenantId(),id,input)!=1)throw new DomainException(DomainException.Code.CONFLICT,"授权版本已变化");
   return mapper.find(actor.tenantId(),id);
  });
 }
 /** 全租户授权列表仅平台管理员可见。 */
 public List<Grant> list(Actor actor,String after,int limit){actor.requireAdmin();Inputs.page(after,limit);return mapper.list(actor.tenantId(),after,limit);}
 /** 查询在数据库中过滤范围，撤销后不会返回旧目录。 */
 public List<StoreApi.View> stores(Actor actor,String after,int limit){Inputs.page(after,limit);if(actor.role()==Actor.Role.ADMIN)return stores.list(actor,after,limit);operator(actor);return mapper.stores(actor.tenantId(),actor.actorId(),after,limit);}
 /** 不可见店铺与跨租户资源由店铺API统一拒绝。 */
 public void requireCatalog(Actor actor,String id){var store=stores.requireActive(actor,id);if(actor.role()==Actor.Role.ADMIN)return;operator(actor);if(!mapper.allowed(actor.tenantId(),actor.actorId(),id,store.merchantId()))throw new DomainException(DomainException.Code.FORBIDDEN,"没有该店铺商品经营权限");}
 private void operator(Actor actor){if(actor.role()!=Actor.Role.OPERATOR)throw new DomainException(DomainException.Code.FORBIDDEN,"需要运营身份");}
}
