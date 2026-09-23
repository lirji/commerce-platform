package com.lrj.commerce.ops.application;
import com.lrj.commerce.ops.api.OpsPageApi;
import com.lrj.commerce.ops.infrastructure.OpsPageMapper;
import com.lrj.commerce.runtime.*;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.kernel.*;
import com.lrj.commerce.store.api.StoreApi;
import com.lrj.commerce.campaign.api.*;
import com.lrj.commerce.benefit.api.*;
import com.lrj.commerce.journey.api.JourneyApi;
import org.springframework.stereotype.Service;
import java.util.*;
/** 低代码页面组合已有用例，发布权限和幂等仍在服务端执行。 */
@Service
public class OpsPageService implements OpsPageApi {
    private final OpsPageMapper mapper;
    private final Commands commands;
    private final StoreApi stores;
    private final CampaignApi campaigns;
    private final CouponApi coupons;
    private final JourneyApi journeys;
    private final EntitlementApi benefits;
    private final CampaignFundingApi budgets;
    public OpsPageService(OpsPageMapper mapper,Commands commands,StoreApi stores,CampaignApi campaigns,CouponApi coupons,JourneyApi journeys,EntitlementApi benefits,CampaignFundingApi budgets){this.mapper=mapper;this.commands=commands;this.stores=stores;this.campaigns=campaigns;this.coupons=coupons;this.journeys=journeys;this.benefits=benefits;this.budgets=budgets;}
    private void validate(Definition input){
        Inputs.require(input!=null&&input.version()>0,"页面版本无效");Identifiers.require(input.pageId());Identifiers.require(input.storeId());Inputs.text(input.title(),128);
        Inputs.require(input.sections()!=null&&!input.sections().isEmpty()&&input.sections().size()<=8&&input.actions()!=null&&input.actions().size()<=4,"页面组件数量无效");
        var ids=new HashSet<String>();
        for(var s:input.sections()){Inputs.require(s!=null&&s.source()!=null,"数据源缺失");Identifiers.require(s.id());Inputs.text(s.title(),128);Inputs.require(ids.add(s.id()),"组件标识重复");}
        for(var a:input.actions()){Inputs.require(a!=null&&a.kind()!=null,"动作类型缺失");Identifiers.require(a.id());Inputs.text(a.label(),64);Inputs.require(ids.add(a.id()),"组件标识重复");}
    }
    /** 定义内容不可变，最大50版本使管理操作锁范围有界。 */
    public View create(Actor actor,String key,Definition input){actor.requireAdmin();validate(input);return commands.run(actor,"ops-page.create",key,input,View.class,()->{
        stores.requireActive(actor,input.storeId());var versions=mapper.lockGroup(actor.tenantId(),input.pageId());Inputs.require(versions.size()<50,"页面版本最多50个");
        mapper.insert(actor.tenantId(),input,JsonCodec.write(input));return view(mapper.find(actor.tenantId(),input.pageId(),input.version()));
    });}
    /** 最新版本列表不代表已发布版本，渲染入口另查发布指针。 */
    public List<View> list(Actor actor,String after,int limit){actor.requireAdmin();Inputs.page(after,limit);return mapper.list(actor.tenantId(),after,limit).stream().map(this::view).toList();}
    public List<View> versions(Actor actor,String id){actor.requireAdmin();Identifiers.require(id);return mapper.versions(actor.tenantId(),id).stream().map(this::view).toList();}
    /** 回退只能激活曾经发布过的暂停版本，不能跳过审批。 */
    public View change(Actor actor,String key,String id,long version,long expected,String action){
        actor.requireAdmin();Identifiers.require(id);Inputs.require(version>0&&expected>=0&&Set.of("submit","approve","reject","publish","pause","rollback").contains(action),"页面审批参数无效");
        return commands.run(actor,"ops-page."+action,key,List.of(id,version,expected),View.class,()->{
            var row=locked(actor,id,version);
            boolean allowed=switch(action){case "submit"->row.status().equals("DRAFT");case "approve","reject"->row.status().equals("IN_REVIEW");case "publish"->Set.of("APPROVED","PAUSED").contains(row.status());case "rollback"->row.status().equals("PAUSED");case "pause"->row.status().equals("PUBLISHED");default->false;};
            if(!allowed||row.lockVersion()!=expected)throw conflict("页面状态或版本冲突");
            String next=switch(action){case "submit"->"IN_REVIEW";case "approve"->"APPROVED";case "reject"->"REJECTED";case "publish","rollback"->"PUBLISHED";default->"PAUSED";};
            if(next.equals("PUBLISHED")){stores.requireActive(actor,view(row).content().storeId());mapper.pauseOthers(actor.tenantId(),id);}
            if(mapper.change(actor.tenantId(),id,version,expected,next)!=1)throw conflict("页面状态并发修改");return view(mapper.find(actor.tenantId(),id,version));
        });
    }
    /** 预览读取真实业务数据但不写命令、审批或业务状态。 */
    public Render preview(Actor actor,Definition input){actor.requireAdmin();validate(input);stores.requireActive(actor,input.storeId());return render(actor,new View(input,"PREVIEW",0),true);}
    /** 只有已发布的完整定义可作为操作页面。 */
    public Render render(Actor actor,String id){actor.requireAdmin();Identifiers.require(id);return render(actor,view(Inputs.found(mapper.published(actor.tenantId(),id))),false);}
    private Render render(Actor actor,View page,boolean preview){
        var data=page.content().sections().stream().map(s->new SectionData(s.id(),switch(s.source()){
            case CAMPAIGNS->campaigns.list(actor,"",20);case JOURNEYS->journeys.definitions(actor,"",20);case BUDGETS->budgets.budgets(actor,"",20);
            case COUPONS->coupons.definitions(actor,page.content().storeId(),"",20);case ENTITLEMENTS->benefits.definitions(actor,page.content().storeId(),"",20);
        })).toList();return new Render(page,data,preview,true);
    }
    /** 页面版本锁与业务操作同事务，暂停并发不会穿透发布状态校验。 */
    public ActionResult execute(Actor actor,String key,String id,long version,String action,ActionInput input){
        actor.requireAdmin();Identifiers.require(id);Identifiers.require(action);Inputs.require(input!=null&&version>0,"页面动作入参无效");
        return commands.run(actor,"ops-page.execute",key,List.of(id,version,action,input),ActionResult.class,()->{
            var row=locked(actor,id,version);if(!row.status().equals("PUBLISHED"))throw conflict("页面版本未发布或已被替换");var page=view(row).content();
            var choice=page.actions().stream().filter(a->a.id().equals(action)).findFirst().orElseThrow(()->new DomainException(DomainException.Code.NOT_FOUND,"页面未声明此动作"));
            int count=(input.campaign()==null?0:1)+(input.coupon()==null?0:1)+(input.enrollment()==null?0:1);Inputs.require(count==1,"动作必须且只能携带一种业务请求");
            String childKey=JsonCodec.hash(id+"/"+version+"/"+action+"/"+key);
            return switch(choice.kind()){
                case CREATE_CAMPAIGN->{Inputs.require(input.campaign()!=null&&page.storeId().equals(input.campaign().storeId()),"活动请求或店铺不匹配");var created=campaigns.create(actor,childKey,input.campaign());yield new ActionResult(choice.kind(),created.content().campaignId(),created.status());}
                case CREATE_COUPON->{Inputs.require(input.coupon()!=null&&page.storeId().equals(input.coupon().storeId()),"券请求或店铺不匹配");var created=coupons.create(actor,childKey,input.coupon());yield new ActionResult(choice.kind(),created.content().definitionId(),"CREATED");}
                case ENROLL_JOURNEY->{Inputs.require(input.enrollment()!=null,"旅程入组请求缺失");var created=journeys.enroll(actor,childKey,input.enrollment());yield new ActionResult(choice.kind(),created.instanceId(),created.status().name());}
            };
        });
    }
    private OpsPageMapper.Row locked(Actor actor,String id,long version){return mapper.lockGroup(actor.tenantId(),id).stream().filter(r->r.version()==version).findFirst().orElseThrow(()->new DomainException(DomainException.Code.NOT_FOUND,"页面版本不存在"));}
    private View view(OpsPageMapper.Row row){return new View(JsonCodec.read(row.definitionJson(),Definition.class),row.status(),row.lockVersion());}
    private DomainException conflict(String message){return new DomainException(DomainException.Code.CONFLICT,message);}
}
