package com.lrj.commerce.catalog.application;
import com.lrj.commerce.catalog.api.ProductOperationsApi;
import com.lrj.commerce.catalog.infrastructure.*;
import com.lrj.commerce.store.api.StoreAccessApi;
import com.lrj.commerce.runtime.*;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import java.util.*;
import java.math.BigDecimal;

/** 规格不可换绑，商品修订原子写当前投影和历史，不重写已成交报价。 */
@Service
public class ProductOperationsService implements ProductOperationsApi {
 private final ProductMapper mapper;private final CatalogMapper catalog;private final StoreAccessApi access;private final Commands commands;
 public ProductOperationsService(ProductMapper mapper,CatalogMapper catalog,StoreAccessApi access,Commands commands){this.mapper=mapper;this.catalog=catalog;this.access=access;this.commands=commands;}
 /** 商品主数据归属门店，授权检查放在命令内使重试也遵循事务。 */
 public Product create(Actor actor,String key,ProductInput input){
  Inputs.require(input!=null,"商品不能为空");Identifiers.require(input.productId());metadata(input.title(),input.category(),input.brand());access.requireCatalog(actor,input.storeId());
  return commands.run(actor,"product.create",key,input,Product.class,()->{access.requireCatalog(actor,input.storeId());mapper.insertProduct(actor.tenantId(),input);return mapper.product(actor.tenantId(),input.storeId(),input.productId());});
 }
 /** 元资料版本与SKU售价版本分开，分类变更不改历史价格。 */
 public Product changeProduct(Actor actor,String key,String id,ProductChange input){
  Identifiers.require(id);Inputs.require(input!=null&&input.expectedVersion()>=0,"版本无效");metadata(input.title(),input.category(),input.brand());access.requireCatalog(actor,input.storeId());
  return commands.run(actor,"product.change",key,new Object[]{id,input},Product.class,()->{access.requireCatalog(actor,input.storeId());Inputs.found(mapper.product(actor.tenantId(),input.storeId(),id));check(mapper.changeProduct(actor.tenantId(),id,input));return mapper.product(actor.tenantId(),input.storeId(),id);});
 }
 /** 数据库游标保证读取有界。 */
 public List<Product> products(Actor actor,String store,String after,int limit){access.requireCatalog(actor,store);Inputs.page(after,limit);return mapper.products(actor.tenantId(),store,after,limit);}
 /** 名值对规范化后唯一，避免仅靠前端防重复。 */
 public Sku variant(Actor actor,String key,Variant input){
  Inputs.require(input!=null,"SKU不能为空");Identifiers.require(input.skuId());Identifiers.require(input.productId());Inputs.text(input.title(),128);access.requireCatalog(actor,input.storeId());
  Inputs.require(input.specifications()!=null&&!input.specifications().isEmpty()&&input.specifications().size()<=8,"规格数量需为1至8");
  var specs=new TreeMap<String,String>();for(var spec:input.specifications()){Inputs.require(spec!=null,"规格不能为空");String name=Inputs.text(spec.name(),64).strip(),value=Inputs.text(spec.value(),64).strip();Inputs.require(specs.put(name,value)==null,"规格名称不能重复");}
  var normalized=new Variant(input.skuId(),input.productId(),input.storeId(),input.title(),price(input.unitPrice()),specs.entrySet().stream().map(e->new Specification(e.getKey(),e.getValue())).toList());
  return commands.run(actor,"product.variant",key,normalized,Sku.class,()->{
   access.requireCatalog(actor,input.storeId());Inputs.found(mapper.product(actor.tenantId(),input.storeId(),input.productId()));String json=JsonCodec.write(normalized.specifications());
   mapper.insertVariant(actor.tenantId(),normalized,json,JsonCodec.hash(json));catalog.snapshot(actor.tenantId(),input.skuId(),"新建待上架规格",actor.actorId());return view(mapper.sku(actor.tenantId(),input.storeId(),input.skuId()));
  });
 }
 /** 售价、状态和标题使用同一修订，避免运营看到半次变更。 */
 public Sku change(Actor actor,String key,String id,Change input){
  Identifiers.require(id);Inputs.require(input!=null&&input.expectedVersion()>0,"版本无效");Inputs.text(input.title(),128);Inputs.text(input.reason(),256);Inputs.require("ACTIVE".equals(input.status())||"FROZEN".equals(input.status()),"状态无效");access.requireCatalog(actor,input.storeId());
  var normalized=new Change(input.storeId(),input.expectedVersion(),input.title(),price(input.unitPrice()),input.status(),input.reason());
  return commands.run(actor,"product.sku.change",key,new Object[]{id,normalized},Sku.class,()->{
   access.requireCatalog(actor,input.storeId());Inputs.found(mapper.sku(actor.tenantId(),input.storeId(),id));check(mapper.change(actor.tenantId(),id,normalized));catalog.snapshot(actor.tenantId(),id,input.reason(),actor.actorId());return view(mapper.sku(actor.tenantId(),input.storeId(),id));
  });
 }
 /** 经营列表包含下架商品，会员目录仍只包含上架商品。 */
 public List<Sku> skus(Actor actor,String store,String after,int limit){access.requireCatalog(actor,store);Inputs.page(after,limit);return mapper.skus(actor.tenantId(),store,after,limit).stream().map(this::view).toList();}
 /** 历史记录也验证当前授权，撤权后不能通过旧链接读取。 */
 public List<Revision> history(Actor actor,String store,String id,long after,int limit){access.requireCatalog(actor,store);Identifiers.require(id);Inputs.require(after>=0,"版本游标无效");Inputs.page("",limit);Inputs.found(mapper.sku(actor.tenantId(),store,id));return mapper.history(actor.tenantId(),store,id,after,limit);}
 private Sku view(ProductMapper.SkuRow r){return new Sku(r.skuId(),r.storeId(),r.title(),r.unitPrice(),r.revision(),r.status(),r.productId(),r.specificationsJson()==null?List.of():Arrays.asList(JsonCodec.read(r.specificationsJson(),Specification[].class)));}
 private void metadata(String title,String category,String brand){Inputs.text(title,128);Inputs.text(category,64);Inputs.text(brand,64);}
 private String price(String value){Inputs.text(value,32);try{return new Money(new BigDecimal(value)).amount().toPlainString();}catch(NumberFormatException e){throw new DomainException(DomainException.Code.INVALID_INPUT,"金额格式无效");}}
 private void check(int count){if(count!=1)throw new DomainException(DomainException.Code.CONFLICT,"商品版本已更新，请刷新后重试");}
}
