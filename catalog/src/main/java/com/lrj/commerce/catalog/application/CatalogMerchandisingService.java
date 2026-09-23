package com.lrj.commerce.catalog.application;
import com.lrj.commerce.catalog.api.*;
import com.lrj.commerce.catalog.infrastructure.*;
import com.lrj.commerce.runtime.*;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.kernel.*;
import com.lrj.commerce.store.api.*;
import org.springframework.stereotype.Service;
import java.util.*;
import java.math.BigDecimal;
import java.net.URI;
/** 商品锁串行化模板绑定与规格创建，元资料不改变历史成交快照。 */
@Service
public class CatalogMerchandisingService implements CatalogMerchandisingApi {
 private final MerchandisingMapper mapper;private final ProductMapper products;private final StoreAccessApi access;private final StoreApi stores;private final Commands commands;
 public CatalogMerchandisingService(MerchandisingMapper mapper,ProductMapper products,StoreAccessApi access,StoreApi stores,Commands commands){this.mapper=mapper;this.products=products;this.access=access;this.stores=stores;this.commands=commands;}
 /** 父节点锁使新建子类目与停用互斥，层级不会在并发下越界。 */
 public Category createCategory(Actor actor,String key,CategoryInput input){Inputs.require(input!=null,"类目不能为空");Identifiers.require(input.categoryId());Inputs.text(input.name(),64);optionalId(input.parentId());access.requireCatalog(actor,input.storeId());
  return commands.run(actor,"catalog.category.create",key,input,Category.class,()->{access.requireCatalog(actor,input.storeId());int depth=1;if(input.parentId()!=null){var parent=activeCategory(actor.tenantId(),input.storeId(),input.parentId());depth=parent.depth()+1;}Inputs.require(depth<=3,"类目最多三级");mapper.categoryInsert(actor.tenantId(),input,depth);return mapper.categoryLock(actor.tenantId(),input.storeId(),input.categoryId());});
 }
 /** 不删除使用中的类目，停用后也不能再创建子节点或绑定商品。 */
 public Category changeCategory(Actor actor,String key,String id,CategoryChange input){Identifiers.require(id);Inputs.require(input!=null&&input.expectedVersion()>=0,"类目版本无效");Inputs.text(input.name(),64);Inputs.text(input.reason(),256);Inputs.require("ACTIVE".equals(input.status())||"RETIRED".equals(input.status()),"类目状态无效");access.requireCatalog(actor,input.storeId());
  return commands.run(actor,"catalog.category.change",key,new Object[]{id,input},Category.class,()->{access.requireCatalog(actor,input.storeId());var row=Inputs.found(mapper.categoryLock(actor.tenantId(),input.storeId(),id));
   if(input.status().equals("RETIRED")&&mapper.categoryUsed(actor.tenantId(),input.storeId(),id))throw conflict("类目仍有启用子类目或绑定商品");
   if(input.status().equals("ACTIVE")&&row.parentId()!=null)activeCategory(actor.tenantId(),input.storeId(),row.parentId());check(mapper.categoryChange(actor.tenantId(),id,input));return mapper.categoryLock(actor.tenantId(),input.storeId(),id);
  });
 }
 /** 管理端包含停用项，销售端只显示启用类目。 */
 public List<Category> categories(Actor actor,String store,String after,int limit,boolean publicOnly){if(publicOnly)stores.requireActive(actor,store);else access.requireCatalog(actor,store);Inputs.page(after,limit);return mapper.categories(actor.tenantId(),store,after,limit,publicOnly);}
 /** 属性及值规范化后不可变，旧SKU始终按绑定版本校验。 */
 public Template createTemplate(Actor actor,String key,Template input){Inputs.require(input!=null&&input.version()>0,"模板版本无效");Identifiers.require(input.templateId());Inputs.text(input.name(),128);access.requireCatalog(actor,input.storeId());Inputs.require(input.fields()!=null&&!input.fields().isEmpty()&&input.fields().size()<=8,"模板需1至8个属性");
  var fields=new ArrayList<Attribute>();var names=new HashSet<String>();for(var field:input.fields()){Inputs.require(field!=null,"属性缺失");String name=Inputs.text(field.name(),64).strip();Inputs.require(!name.isEmpty()&&names.add(name),"属性名称为空或重复");Inputs.require(field.values()!=null&&!field.values().isEmpty()&&field.values().size()<=50,"允许值需1至50个");var values=new TreeSet<String>();for(var value:field.values()){String normalized=Inputs.text(value,64).strip();Inputs.require(!normalized.isEmpty()&&values.add(normalized),"允许值为空或重复");}fields.add(new Attribute(name,List.copyOf(values)));}
  var normalized=new Template(input.templateId(),input.version(),input.storeId(),input.name(),List.copyOf(fields));return commands.run(actor,"catalog.template.create",key,normalized,Template.class,()->{access.requireCatalog(actor,input.storeId());mapper.templateInsert(actor.tenantId(),normalized,JsonCodec.write(normalized));return normalized;});
 }
 /** 按模板ID显示最新版本，详情绑定仍使用精确版本接口。 */
 public List<Template> templates(Actor actor,String store,String after,int limit){access.requireCatalog(actor,store);Inputs.page(after,limit);return mapper.templates(actor.tenantId(),store,after,limit).stream().map(v->JsonCodec.read(v,Template.class)).toList();}
 /** 明确版本用于旧绑定的规格编辑，不自动换成新模板。 */
 public Template template(Actor actor,String store,String id,long version){access.requireCatalog(actor,store);Identifiers.require(id);Inputs.require(version>0,"模板版本无效");return JsonCodec.read(Inputs.found(mapper.template(actor.tenantId(),store,id,version)),Template.class);}
 /** 缺省详情返回版本0，便于老商品渐进补齐。 */
 public Profile profile(Actor actor,String store,String product){access.requireCatalog(actor,store);Identifiers.require(product);Inputs.found(products.product(actor.tenantId(),store,product));return profile(mapper.profile(actor.tenantId(),store,product),product,store);}
 /** 图文资料有独立版本；绑定规格模板必须发生在第一个SKU之前。 */
 public Profile changeProfile(Actor actor,String key,String product,ProfileChange input){Identifiers.require(product);Inputs.require(input!=null&&input.expectedVersion()>=0,"资料版本无效");access.requireCatalog(actor,input.storeId());optionalId(input.categoryId());optionalId(input.templateId());Inputs.require((input.templateId()==null&&input.templateVersion()==null)||(input.templateId()!=null&&input.templateVersion()!=null&&input.templateVersion()>0),"模板标识与版本需同时填写");Inputs.require(input.description()!=null&&input.description().length()<=5000,"说明最多5000字");Inputs.text(input.reason(),256);Inputs.require(input.images()!=null&&input.images().size()<=6,"图片最多6张");for(var picture:input.images())validatePicture(picture);
  return commands.run(actor,"catalog.profile.change",key,new Object[]{product,input},Profile.class,()->{access.requireCatalog(actor,input.storeId());Inputs.found(products.productLock(actor.tenantId(),input.storeId(),product));if(input.categoryId()!=null)activeCategory(actor.tenantId(),input.storeId(),input.categoryId());var old=mapper.profileCurrent(actor.tenantId(),input.storeId(),product);
   if(old!=null&&old.templateId()!=null&&(!Objects.equals(old.templateId(),input.templateId())||!Objects.equals(old.templateVersion(),input.templateVersion())))throw conflict("已有规格模板不可换绑，请创建新商品");
   if(input.templateId()!=null){Inputs.found(mapper.template(actor.tenantId(),input.storeId(),input.templateId(),input.templateVersion()));if((old==null||old.templateId()==null)&&mapper.hasVariants(actor.tenantId(),input.storeId(),product))throw conflict("已有自由规格商品不可补绑模板");}
   if(old==null){if(input.expectedVersion()!=0)throw conflict("商品详情版本已变化");mapper.profileInsert(actor.tenantId(),product,input,JsonCodec.write(input.images()));}else check(mapper.profileChange(actor.tenantId(),product,input,JsonCodec.write(input.images())));
   return profile(mapper.profileCurrent(actor.tenantId(),input.storeId(),product),product,input.storeId());
  });
 }
 /** 管理端才读取条码修订版本。 */
 public Barcode barcode(Actor actor,String store,String sku){access.requireCatalog(actor,store);Identifiers.require(sku);Inputs.found(products.sku(actor.tenantId(),store,sku));var row=mapper.barcode(actor.tenantId(),store,sku);return row==null?new Barcode(sku,store,null,0):row;}
 /** SKU锁统一同一SKU编辑；不同SKU重复条码由数据库唯一键裁决。 */
 public Barcode changeBarcode(Actor actor,String key,String sku,BarcodeChange input){Identifiers.require(sku);Inputs.require(input!=null&&input.expectedVersion()>=0,"条码版本无效");access.requireCatalog(actor,input.storeId());Inputs.text(input.reason(),256);String raw=input.barcode()==null||input.barcode().isBlank()?null:input.barcode().strip();Inputs.require(raw==null||raw.matches("[A-Za-z0-9._-]{1,64}"),"条码需为1至64位字母数字或._-");String value=raw==null?null:raw.toUpperCase(Locale.ROOT);var normalized=new BarcodeChange(input.storeId(),input.expectedVersion(),value,input.reason());
  return commands.run(actor,"catalog.barcode.change",key,new Object[]{sku,normalized},Barcode.class,()->{access.requireCatalog(actor,input.storeId());Inputs.found(products.skuLock(actor.tenantId(),input.storeId(),sku));var old=mapper.barcodeCurrent(actor.tenantId(),input.storeId(),sku);if(old==null){if(input.expectedVersion()!=0)throw conflict("条码版本已变化");mapper.barcodeInsert(actor.tenantId(),sku,normalized);}else check(mapper.barcodeChange(actor.tenantId(),sku,normalized));return mapper.barcodeCurrent(actor.tenantId(),input.storeId(),sku);});
 }
 /** 所有筛选值参数化，稳定ID分页；销售目录不能通过status查询下架货品。 */
 public List<Item> search(Actor actor,Search input,boolean operations){Inputs.require(input!=null,"检索参数缺失");if(operations)access.requireCatalog(actor,input.storeId());else stores.requireActive(actor,input.storeId());Inputs.page(input.after(),input.limit());optionalId(input.categoryId());String q=input.q()==null||input.q().isBlank()?null:Inputs.text(input.q(),64).strip();String status=input.status()==null||input.status().isBlank()?null:input.status();Inputs.require(status==null||status.equals("ACTIVE")||status.equals("FROZEN"),"销售状态无效");String min=price(input.minimumPrice()),max=price(input.maximumPrice());Inputs.require(min==null||max==null||new BigDecimal(min).compareTo(new BigDecimal(max))<=0,"最低价格不能超过最高价格");var normalized=new Search(input.storeId(),q,input.categoryId(),min,max,operations?status:"ACTIVE",input.after(),input.limit());return mapper.search(actor.tenantId(),normalized).stream().map(this::item).toList();}
 /** 详情只返回当前可售商品，不暴露经营修订和操作者。 */
 public Item item(Actor actor,String store,String sku){stores.requireActive(actor,store);Identifiers.require(sku);return item(Inputs.found(mapper.item(actor.tenantId(),store,sku)));}
 private Item item(MerchandisingMapper.ItemRow row){return new Item(row.skuId(),row.storeId(),row.title(),row.unitPrice(),row.revision(),row.status(),row.productId(),row.categoryId(),row.categoryName(),row.barcode(),row.specificationsJson()==null?List.of():Arrays.asList(JsonCodec.read(row.specificationsJson(),ProductOperationsApi.Specification[].class)),row.description(),pictures(row.imagesJson()));}
 private Profile profile(MerchandisingMapper.ProfileRow row,String product,String store){return row==null?new Profile(product,store,null,null,null,"",List.of(),0):new Profile(row.productId(),row.storeId(),row.categoryId(),row.templateId(),row.templateVersion(),row.description(),pictures(row.imagesJson()),row.version());}
 private List<Picture> pictures(String json){return json==null?List.of():Arrays.asList(JsonCodec.read(json,Picture[].class));}
 private Category activeCategory(String tenant,String store,String id){var category=Inputs.found(mapper.categoryLock(tenant,store,id));if(!category.status().equals("ACTIVE"))throw conflict("类目已停用");return category;}
 private void optionalId(String id){if(id!=null)Identifiers.require(id);}
 private String price(String value){if(value==null||value.isBlank())return null;Inputs.text(value,32);try{return new Money(new BigDecimal(value)).amount().toPlainString();}catch(NumberFormatException error){throw new DomainException(DomainException.Code.INVALID_INPUT,"价格格式无效");}}
 private void validatePicture(Picture picture){Inputs.require(picture!=null,"图片不能为空");Inputs.text(picture.url(),2048);Inputs.text(picture.alt(),128);try{var uri=URI.create(picture.url());boolean local=picture.url().matches("/media/[A-Za-z0-9/_-]+\\.(png|jpg|jpeg|webp|svg)");boolean remote="https".equalsIgnoreCase(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null&&uri.getFragment()==null;Inputs.require(local||remote,"图片仅支持公开HTTPS或站内media路径");}catch(IllegalArgumentException failure){throw new DomainException(DomainException.Code.INVALID_INPUT,"图片地址格式无效");}}
 private void check(int count){if(count!=1)throw conflict("资料版本已变化，请刷新后重试");}
 private DomainException conflict(String message){return new DomainException(DomainException.Code.CONFLICT,message);}
}
