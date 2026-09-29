package com.lrj.commerce.app.iam;

import com.lrj.authz.protocol.CentralAccessDtos.Check;
import com.lrj.authz.protocol.ScopeAccessDtos.Plan;
import com.lrj.authz.protocol.ScopeDtos;
import com.lrj.authz.sdk.CentralAccessClient;
import com.lrj.authz.sdk.AccessDeniedException;
import com.lrj.commerce.app.iam.ScopeWork.*;
import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.api.scope.ScopeQuery;
import com.lrj.commerce.runtime.command.Commands;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 中央范围用例：非HTTP调用同样重新认证；资源表只通过其Owner API读取。 */
public final class CentralScopeService {
    private static final int BATCH_SIZE=50,MAX_EXPORT_ROWS=1000,MAX_TENANT_JOBS=10,MAX_USER_JOBS=2;
    private final CentralAccessClient client;
    private final CentralStoreBindingMapper bindings;
    private final ScopeWorkMapper work;
    private final Commands commands;
    private final Map<String,ScopeQuery> resources;
    /** 只装配真实存在的两种资源Owner，不提供任意查询或动态表名。 */
    public CentralScopeService(CentralAccessClient client,CentralStoreBindingMapper bindings,ScopeWorkMapper work,Commands commands,List<ScopeQuery> resources){
        this.client=client;this.bindings=bindings;this.work=work;this.commands=commands;
        this.resources=resources.stream().collect(Collectors.toUnmodifiableMap(ScopeQuery::resourceType,Function.identity()));
        if(!this.resources.keySet().equals(Set.of(ScopeDtos.STORE_RESOURCE_TYPE,ScopeDtos.PRODUCT_RESOURCE_TYPE)))throw new IllegalStateException("范围资源Owner不完整");
    }
    /** 过滤器只建立当前请求身份，用例中仍独立获取新plan。 */
    public CentralStoreIdentity authenticate(String token,String tenant,String type){
        var p=client.requireScope(token,check(tenant,null,type));Actor actor=binding(p);
        return new CentralStoreIdentity(token,tenant,p.context().membershipGeneration(),actor);
    }
    /** 列表/搜索/count/统计共享SQL谓词，结果返回前再次验证整个授权上下文。 */
    public Page page(CentralStoreIdentity identity,String type,String cursor,int limit,String search){
        validateQuery(limit,search);var a=authorize(identity,type);String after="";
        if(cursor!=null&&!cursor.isEmpty()){
            uuid(cursor);var saved=work.cursorById(a.actor().tenantId(),cursor);
            if(saved==null||!saved.principalId().equals(a.plan().context().principalId())||!saved.binding().equals(a.binding())||!saved.resourceType().equals(type)||!saved.search().equals(search))throw denied();
            after=saved.afterId();
        }
        var query=owner(type);var rows=query.page(a.actor(),a.filter(),search,after,limit+1);var stats=query.stats(a.actor(),a.filter(),search);
        boolean more=rows.size()>limit;var shown=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));
        recheck(identity,type,a);String next=null;
        if(more){next=id();one(work.cursor(new Cursor(next,a.actor().tenantId(),a.plan().context().principalId(),a.binding(),type,search,shown.getLast().resourceId(),Instant.now().plusSeconds(300))));}
        return new Page(shown,stats.total(),stats.stores(),next);
    }
    /** 详情使用真实资源Owner事实；跨租户缺失与无权统一拒绝，不信任浏览器传归属。 */
    public ScopeQuery.Row detail(CentralStoreIdentity identity,String type,String resource){
        var a=authorize(identity,type);resourceId(resource);var row=owner(type).fact(a.actor(),resource);if(row==null)throw denied();
        var facts=new ScopeDtos.Facts(a.plan().context().tenantId(),type,row.resourceId(),row.resourceVersion(),null,null,List.of(),row.storeId(),null);
        var decision=client.checkResource(identity.userToken(),check(identity.authTenant(),identity.generation(),type),facts);
        if(!"ALLOW".equals(decision.decision())||!decision.context().principalId().equals(a.plan().context().principalId()))throw denied();
        var latest=owner(type).fact(a.actor(),resource);
        if(!row.equals(latest))throw conflict("资源已变化，请重新读取");
        recheck(identity,type,a);return row;
    }
    /** 动作提示也基于当前资源事实；只是体验提示，不替代实际提交的再次检查。 */
    public ProductActions actions(CentralStoreIdentity identity,String resource) {
        detail(identity,ScopeDtos.PRODUCT_RESOURCE_TYPE,resource);
        try { productWrite(identity,resource);return new ProductActions(true); }
        catch(AccessDeniedException denied){return new ProductActions(false);}
    }
    /** 只有受控商品元资料写入使用该端口；中央网络判权不延长数据库事务。 */
    public com.lrj.commerce.catalog.product.api.ProductOperationsApi.Product changeProduct(CentralStoreIdentity identity,String resource,
            com.lrj.commerce.catalog.product.api.ScopedProductOperations.MetadataChange input,String key,
            com.lrj.commerce.catalog.product.api.ScopedProductOperations products) {
        var a=productWrite(identity,resource);var row=owner(ScopeDtos.PRODUCT_RESOURCE_TYPE).fact(a.actor(),resource);
        if(row==null)throw denied();
        // 完整范围仍进入Owner SQL；资源版本或归属在判权后变化时，不会借旧事实写入其他范围。
        var current=authorize(identity,ScopeDtos.PRODUCT_RESOURCE_TYPE,PRODUCT_UPDATE);CentralAccessClient.requireSameScope(a.plan(),current.plan());
        Instant deadline=Instant.now().plusSeconds(5),validUntil=Instant.parse(current.plan().validUntil());
        if(validUntil.isBefore(deadline))deadline=validUntil;
        return products.changeScoped(current.actor(),current.filter(),deadline,current.plan().context().principalId()+":"+current.plan().context().membershipId()+":"+identity.generation(),key,resource,row.storeId(),input);
    }
    /** 对单条真实商品判定update，绝不拼接read来源的范围。 */
    private Authorized productWrite(CentralStoreIdentity identity,String resource) {
        resourceId(resource);var a=authorize(identity,ScopeDtos.PRODUCT_RESOURCE_TYPE,PRODUCT_UPDATE);
        var row=owner(ScopeDtos.PRODUCT_RESOURCE_TYPE).fact(a.actor(),resource);if(row==null)throw denied();
        var facts=new ScopeDtos.Facts(a.plan().context().tenantId(),ScopeDtos.PRODUCT_RESOURCE_TYPE,row.resourceId(),row.resourceVersion(),null,null,List.of(),row.storeId(),null);
        var decision=client.checkResource(identity.userToken(),new Check(identity.authTenant(),identity.generation(),id(),PRODUCT_UPDATE,ScopeDtos.PRODUCT_RESOURCE_TYPE),facts);
        if(!"ALLOW".equals(decision.decision())||!decision.context().principalId().equals(a.plan().context().principalId()) || decision.context().membershipGeneration()!=a.plan().context().membershipGeneration())throw denied();
        return a;
    }
    /** 仅返回当前对象的最小动作提示，不返回Grant或员工目录。 */
    public record ProductActions(boolean update) {}
    private static final String PRODUCT_UPDATE="commerce.product.update";
    /** 提交时确认范围与有界容量；幂等命令、任务和审计在同一本地事务。 */
    public JobView submit(CentralStoreIdentity identity,String type,String search,String key){
        validateQuery(1,search);var a=authorize(identity,type);
        if(owner(type).stats(a.actor(),a.filter(),search).total()>MAX_EXPORT_ROWS)throw limit();
        recheck(identity,type,a);
        var input=List.of(type,search,a.binding(),a.plan().context().principalId());
        return commands.run(a.actor(),"central.export.submit",key,input,JobView.class,()->{
            work.quota(a.actor().tenantId());if(work.lockQuota(a.actor().tenantId())==null)throw conflict("导出配额不可用");
            if(work.active(a.actor().tenantId(),null)>=MAX_TENANT_JOBS||work.active(a.actor().tenantId(),a.plan().context().principalId())>=MAX_USER_JOBS)throw limit();
            var c=a.plan().context();var job=new Job(id(),a.actor().tenantId(),c.principalId(),c.membershipId(),c.membershipGeneration(),a.binding(),type,search,State.SUBMITTED.code(),"",0,1,Instant.now().plusSeconds(900));
            one(work.insert(job));return view(job);
        });
    }
    /** 实际开始与提交分开，排队期间的撤权不能被旧提交状态覆盖。 */
    public JobView start(CentralStoreIdentity identity,String type,String jobId,long version,String key){
        var a=authorize(identity,type);owned(work.job(a.actor().tenantId(),uuid(jobId)),a,type);recheck(identity,type,a);
        return commands.run(a.actor(),"central.export.start",key,List.of(jobId,version,a.binding()),JobView.class,()->{
            var job=owned(work.lock(a.actor().tenantId(),jobId),a,type);version(job,version,State.SUBMITTED);
            one(work.start(a.actor().tenantId(),jobId,version));return view(work.job(a.actor().tenantId(),jobId));
        });
    }
    /** 每次至多50行，崩溃重放由命令回执和原子检查点保证不重复、不遗漏。 */
    public JobView advance(CentralStoreIdentity identity,String type,String jobId,long version,String key){
        var a=authorize(identity,type);owned(work.job(a.actor().tenantId(),uuid(jobId)),a,type);recheck(identity,type,a);
        return commands.run(a.actor(),"central.export.batch",key,List.of(jobId,version,a.binding()),JobView.class,()->{
            var job=owned(work.lock(a.actor().tenantId(),jobId),a,type);version(job,version,State.RUNNING);
            var page=owner(type).page(a.actor(),a.filter(),job.search(),job.afterId(),BATCH_SIZE+1);
            boolean last=page.size()<=BATCH_SIZE;var batch=page.subList(0,Math.min(BATCH_SIZE,page.size()));
            if(job.rowCount()+batch.size()>MAX_EXPORT_ROWS||(!last&&job.rowCount()+batch.size()==MAX_EXPORT_ROWS))throw limit();
            int sequence=job.rowCount();for(var row:batch){one(work.row(job.tenantId(),job.id(),new ExportRow(++sequence,row.resourceId(),row.resourceVersion(),JsonCodec.write(row))));}
            String after=batch.isEmpty()?job.afterId():batch.getLast().resourceId();
            one(work.advance(job.tenantId(),job.id(),version,after,batch.size(),last?State.COMPLETED.code():State.RUNNING.code()));
            return view(work.job(job.tenantId(),job.id()));
        });
    }
    /** 状态查询也绑定申请主体，不能通过猜任务ID查看其他人的进度。 */
    public JobView job(CentralStoreIdentity identity,String type,String jobId){var a=authorize(identity,type);return view(owned(work.job(a.actor().tenantId(),uuid(jobId)),a,type));}
    /** 下载每批重新判权并核对当前资源归属/版本；完成的历史文件也不能绕过撤权。 */
    public Download download(CentralStoreIdentity identity,String type,String jobId){
        var a=authorize(identity,type);var job=owned(work.job(a.actor().tenantId(),uuid(jobId)),a,type);
        if(!State.COMPLETED.code().equals(job.state()))throw conflict("导出尚未完成");
        List<ScopeQuery.Row> rows=new ArrayList<>();int after=0;
        for(int batch=0;batch<=MAX_EXPORT_ROWS/BATCH_SIZE;batch++){
            var current=authorize(identity,type);CentralAccessClient.requireSameScope(a.plan(),current.plan());
            var stored=work.rows(a.actor().tenantId(),jobId,after);if(stored.isEmpty())break;
            var ids=stored.stream().map(ExportRow::resourceId).toList();var facts=owner(type).current(a.actor(),current.filter(),ids).stream().collect(Collectors.toMap(ScopeQuery.Row::resourceId,Function.identity()));
            if(facts.size()!=stored.size())throw denied();
            for(var snapshot:stored){var row=JsonCodec.read(snapshot.snapshotJson(),ScopeQuery.Row.class);if(!row.equals(facts.get(snapshot.resourceId()))||row.resourceVersion()!=snapshot.resourceVersion())throw conflict("导出资源已变化，请重新生成");rows.add(row);}
            after=stored.getLast().sequence();
        }
        if(rows.size()!=job.rowCount()||rows.size()>MAX_EXPORT_ROWS)throw conflict("导出检查点不完整");
        recheck(identity,type,a);return new Download(jobId,type,List.copyOf(rows));
    }
    private Authorized authorize(CentralStoreIdentity identity,String type){return authorize(identity,type,"commerce."+type+".read");}
    private Authorized authorize(CentralStoreIdentity identity,String type,String capability){
        if(identity==null)throw denied();var plan=client.requireScope(identity.userToken(),new Check(identity.authTenant(),identity.generation(),id(),capability,type));var actor=binding(plan);
        if(!actor.equals(identity.actor())||plan.context().membershipGeneration()!=identity.generation())throw denied();
        return new Authorized(plan,actor,filter(plan),CentralAccessClient.scopeFingerprint(plan));
    }
    private void recheck(CentralStoreIdentity identity,String type,Authorized before){var after=authorize(identity,type);CentralAccessClient.requireSameScope(before.plan(),after.plan());}
    private Actor binding(Plan plan){var c=plan.context();Actor actor=bindings.find(c.tenantId(),c.principalId(),c.membershipId(),c.membershipGeneration());if(actor==null||actor.role()!=Actor.Role.OPERATOR)throw denied();return actor;}
    private ScopeQuery owner(String type){var owner=resources.get(type);if(owner==null)throw new IllegalArgumentException("未知范围资源");return owner;}
    private Check check(String tenant,Long generation,String type){owner(type);return new Check(tenant,generation,id(),"commerce."+type+".read",type);}
    private ScopeQuery.Filter filter(Plan plan){
        List<ScopeQuery.Path> paths=new ArrayList<>();
        for(var a:plan.alternatives()){
            boolean all=false;List<String> stores=List.of(),resources=List.of();
            for(var c:a.clauses())switch(c.kind()){
                case TENANT_ALL -> all=true;
                case SPECIFIED_STORES -> stores=c.values();
                case SPECIFIED_RESOURCES -> resources=c.values();
                default -> throw denied();
            }
            paths.add(new ScopeQuery.Path(all,stores,resources));
        }
        if(paths.isEmpty())throw denied();return new ScopeQuery.Filter(paths);
    }
    private Job owned(Job job,Authorized a,String type){
        var c=a.plan().context();if(job==null||!job.principalId().equals(c.principalId())||!job.membershipId().equals(c.membershipId())||job.generation()!=c.membershipGeneration()
            ||!job.binding().equals(a.binding())||!job.resourceType().equals(type)||!job.expiresAt().isAfter(Instant.now()))throw denied();return job;
    }
    private static void version(Job job,long expected,State state){if(expected<1||job.version()!=expected||!state.code().equals(job.state()))throw conflict("导出检查点或状态已变化");}
    private static JobView view(Job j){return new JobView(j.id(),j.resourceType(),j.state(),j.rowCount(),j.version(),j.expiresAt().toString());}
    private static void validateQuery(int limit,String search){if(limit<1||limit>100||search==null||search.length()>100||search.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("查询参数无效");}
    private static String uuid(String value){try{if(value==null||!UUID.fromString(value).toString().equals(value))throw denied();return value;}catch(IllegalArgumentException e){throw denied();}}
    private static void resourceId(String value){if(value==null||!value.matches("[A-Za-z0-9_:/.-]{1,100}"))throw new IllegalArgumentException("资源标识无效");}
    private static String id(){return UUID.randomUUID().toString();}
    private static void one(int count){if(count!=1)throw conflict("检查点提交冲突");}
    private static AccessDeniedException denied(){return new AccessDeniedException("CENTRAL_SCOPE_DENIED");}
    private static DomainException conflict(String message){return new DomainException(DomainException.Code.CONFLICT,message);}
    private static DomainException limit(){return new DomainException(DomainException.Code.LIMIT_EXCEEDED,"导出超出当前资源预算");}
    private record Authorized(Plan plan,Actor actor,ScopeQuery.Filter filter,String binding){}
}
