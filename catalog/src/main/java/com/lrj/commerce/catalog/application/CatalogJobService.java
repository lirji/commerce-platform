package com.lrj.commerce.catalog.application;
import com.lrj.commerce.catalog.api.*;
import com.lrj.commerce.catalog.infrastructure.*;
import com.lrj.commerce.store.api.StoreAccessApi;
import com.lrj.commerce.runtime.*;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.math.BigDecimal;
import java.util.*;
/** 固定输入逐项执行，冲突不覆盖；每次提交都包含效果、回执和恢复位置。 */
@Service
public class CatalogJobService implements CatalogJobApi {
 private final CatalogJobMapper mapper;private final ProductMapper products;private final ProductOperationsApi operations;private final StoreAccessApi access;private final OperatorDirectory operators;private final Commands commands;private final Clock clock;private final TransactionTemplate tx;private String tenantCursor="";
 public CatalogJobService(CatalogJobMapper mapper,ProductMapper products,ProductOperationsApi operations,StoreAccessApi access,OperatorDirectory operators,Commands commands,Clock clock,PlatformTransactionManager manager){this.mapper=mapper;this.products=products;this.operations=operations;this.access=access;this.operators=operators;this.commands=commands;this.clock=clock;tx=new TransactionTemplate(manager);tx.setTimeout(10);}
 /** runAt空值在幂等边界内补齐，重放不会因为当前时间改变请求hash。 */
 public View create(Actor actor,String key,Create input){Inputs.require(input!=null&&input.action()!=null&&input.deadline()!=null&&input.targets()!=null&&!input.targets().isEmpty()&&input.targets().size()<=100,"经营任务参数无效");Identifiers.require(input.jobId());Inputs.text(input.name(),128);Inputs.text(input.reason(),256);access.requireCatalog(actor,input.storeId());var ids=new HashSet<String>();var targets=new ArrayList<Target>();for(var item:input.targets()){Inputs.require(item!=null&&item.expectedRevision()>0,"预期版本无效");Identifiers.require(item.skuId());Inputs.require(ids.add(item.skuId()),"目标SKU不能重复");String price=null;if(input.action()==Action.PRICE){try{price=new Money(new BigDecimal(Inputs.text(item.unitPrice(),32))).amount().toPlainString();}catch(NumberFormatException e){throw new DomainException(DomainException.Code.INVALID_INPUT,"金额格式无效");}}else Inputs.require(item.unitPrice()==null,"上下架操作不能包含价格");targets.add(new Target(item.skuId(),item.expectedRevision(),price));}
  var normalized=new Create(input.jobId(),input.storeId(),input.name(),input.action(),input.runAt(),input.deadline(),List.copyOf(targets),input.reason());
  return commands.run(actor,"catalog.job.create",key,normalized,View.class,()->{access.requireCatalog(actor,input.storeId());Instant now=now(),run=input.runAt()==null?now:input.runAt();Inputs.require(!run.isBefore(now)&&!run.isAfter(now.plus(Duration.ofDays(30)))&&input.deadline().isAfter(run)&&!input.deadline().isAfter(run.plus(Duration.ofDays(7))),"开始或截止时间无效");for(var item:targets)Inputs.found(products.sku(actor.tenantId(),input.storeId(),item.skuId()));var content=new Create(input.jobId(),input.storeId(),input.name(),input.action(),run,input.deadline(),List.copyOf(targets),input.reason());mapper.insert(actor.tenantId(),content,JsonCodec.write(actor),JsonCodec.write(content),run);return view(mapper.lock(actor.tenantId(),input.jobId()));});
 }
 /** 列表只展示当前授权门店。 */
 public List<View> list(Actor actor,String store,String after,int limit){access.requireCatalog(actor,store);Inputs.page(after,limit);return mapper.list(actor.tenantId(),store,after,limit).stream().map(this::view).toList();}
 /** 回执稳定序号分页，读取仍验证门店边界。 */
 public List<Item> items(Actor actor,String store,String id,int after,int limit){access.requireCatalog(actor,store);Identifiers.require(id);Inputs.require(after>=0,"回执游标无效");Inputs.page("",limit);var row=Inputs.found(mapper.lock(actor.tenantId(),id));if(!row.storeId().equals(store))throw new DomainException(DomainException.Code.NOT_FOUND,"任务不存在");return mapper.items(actor.tenantId(),id,after,limit);}
 /** 控制命令与worker竞争同一行锁；取消不声称撤销已经提交的调价。 */
 public View control(Actor actor,String key,String id,Control input){Identifiers.require(id);Inputs.require(input!=null&&input.expectedVersion()>=0&&Set.of("CANCEL","RETRY").contains(input.action()==null?"":input.action()),"控制参数无效");Inputs.text(input.reason(),256);access.requireCatalog(actor,input.storeId());return commands.run(actor,"catalog.job.control",key,new Object[]{id,input},View.class,()->{access.requireCatalog(actor,input.storeId());var row=Inputs.found(mapper.lock(actor.tenantId(),id));if(!row.storeId().equals(input.storeId()))throw new DomainException(DomainException.Code.NOT_FOUND,"任务不存在");var creator=JsonCodec.read(row.creatorJson(),Actor.class);if(actor.role()!=Actor.Role.ADMIN&&!creator.actorId().equals(actor.actorId()))throw new DomainException(DomainException.Code.FORBIDDEN,"仅创建者或管理员可以控制任务");if(row.version()!=input.expectedVersion())throw conflict();String target;if(input.action().equals("CANCEL")){if(!Set.of("SCHEDULED","RUNNING","ISOLATED").contains(row.status()))throw conflict();target="CANCELLED";}else {if(!row.status().equals("ISOLATED")||!now().isBefore(content(row).deadline()))throw conflict();target="SCHEDULED";}check(mapper.status(actor.tenantId(),id,row.version(),target,now()));return view(mapper.lock(actor.tenantId(),id));});}
 /** 手动推进同样受门店权限约束，上限20项目。 */
 public int pump(Actor actor,String store){access.requireCatalog(actor,store);return pumpTenant(actor.tenantId(),store);}
 /** 每轮最多4租户，每租户20项；游标轮转避免大租户挤占。 */
 public synchronized int tick(){var tenants=mapper.tenants(tenantCursor,now());if(tenants.isEmpty()){tenantCursor="";return 0;}int count=0;for(var tenant:tenants)count+=pumpTenant(tenant,null);tenantCursor=tenants.getLast();return count;}
 private int pumpTenant(String tenant,String store){String id=mapper.pending(tenant,store,now());if(id==null)return 0;int count=0;for(int i=0;i<20;i++){long[] attempted={-1};try{if(!Boolean.TRUE.equals(tx.execute(s->step(tenant,id,attempted))))break;count++;}catch(RuntimeException failure){String code=failure instanceof DomainException d?d.code().name():"STORAGE_FAILURE";tx.executeWithoutResult(s->{var row=mapper.lock(tenant,id); // 失败回滚后重新核对版本，另一执行器已成功时不把错误记到下一项目。
 if(row!=null&&row.version()==attempted[0]&&Set.of("SCHEDULED","RUNNING").contains(row.status()))mapper.failed(tenant,id,code,now().plusSeconds((1L<<Math.min(row.attempts()+1,5))+java.util.concurrent.ThreadLocalRandom.current().nextInt(2)));});break;}}return count;}
 private boolean step(String tenant,String id,long[] attempted){var row=mapper.lock(tenant,id);if(row==null||!Set.of("SCHEDULED","RUNNING").contains(row.status())||row.availableAt().isAfter(now()))return false;attempted[0]=row.version();var input=content(row);if(!now().isBefore(input.deadline())){check(mapper.status(tenant,id,row.version(),"EXPIRED",now()));return false;}var actor=JsonCodec.read(row.creatorJson(),Actor.class);if(actor.role()==Actor.Role.OPERATOR&&!operators.active(tenant,actor.actorId()))throw new DomainException(DomainException.Code.FORBIDDEN,"原经营身份已停用");access.requireCatalog(actor,row.storeId());var target=input.targets().get(row.cursorIndex());var sku=products.skuLock(tenant,row.storeId(),target.skuId());boolean success=sku!=null&&sku.revision()==target.expectedRevision();Long revision=sku==null?null:sku.revision();String reason=sku==null?"SKU_MISSING":"REVISION_CHANGED";
  if(success){String price=input.action()==Action.PRICE?target.unitPrice():sku.unitPrice();String status=switch(input.action()){case PRICE->sku.status();case PUBLISH->"ACTIVE";case UNPUBLISH->"FROZEN";};var changed=operations.change(actor,JsonCodec.hash("catalog-job/"+id+"/"+row.cursorIndex()),sku.skuId(),new ProductOperationsApi.Change(row.storeId(),sku.revision(),sku.title(),price,status,input.reason()));revision=changed.revision();reason="APPLIED";}
  mapper.receipt(tenant,id,new Item(row.cursorIndex()+1,target.skuId(),success?"SUCCEEDED":"CONFLICT",target.expectedRevision(),revision,reason,now()));check(mapper.advance(tenant,id,row.version(),success,row.cursorIndex()+1==input.targets().size()));return true;
 }
 private Create content(CatalogJobMapper.Row row){return JsonCodec.read(row.contentJson(),Create.class);}
 private View view(CatalogJobMapper.Row row){return new View(content(row),row.status(),row.cursorIndex(),row.succeeded(),row.conflicted(),row.attempts(),row.errorCode(),row.version(),JsonCodec.read(row.creatorJson(),Actor.class).actorId());}
 private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MILLIS);}
 private DomainException conflict(){return new DomainException(DomainException.Code.CONFLICT,"任务状态或版本已变化");}
 private void check(int rows){if(rows!=1)throw conflict();}
}
