package com.lrj.commerce.journey.application;
import com.lrj.commerce.journey.api.CouponDeliveryApi;
import com.lrj.commerce.journey.infrastructure.CouponDeliveryMapper;
import com.lrj.commerce.campaign.api.MarketingAssets;
import com.lrj.commerce.member.api.MemberApi;
import com.lrj.commerce.benefit.api.CouponApi;
import com.lrj.commerce.store.api.StoreApi;
import com.lrj.commerce.runtime.*;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
/** 逐收件人提交并持久化恢复位置，不用内存队列代表发券成功。 */
@Service
public class CouponDeliveryService implements CouponDeliveryApi {
    private final CouponDeliveryMapper mapper;private final MarketingAssets audiences;private final MemberApi members;private final CouponApi coupons;private final StoreApi stores;private final Commands commands;private final Clock clock;private final TransactionTemplate tx;private String tenantCursor="";
    public CouponDeliveryService(CouponDeliveryMapper mapper,MarketingAssets audiences,MemberApi members,CouponApi coupons,StoreApi stores,Commands commands,Clock clock,PlatformTransactionManager manager){this.mapper=mapper;this.audiences=audiences;this.members=members;this.coupons=coupons;this.stores=stores;this.commands=commands;this.clock=clock;tx=new TransactionTemplate(manager);tx.setTimeout(10);}
    /** 固定版本与截止时间，创建后不追随更新的人群。 */
    public View create(Actor actor,String key,Create input){
        actor.requireAdmin();Inputs.require(input!=null && input.definitionVersion()>0 && input.deadline()!=null && input.audience()!=null,"发券批次参数无效");Identifiers.require(input.batchId());Identifiers.require(input.definitionId());Inputs.text(input.name(),128);
        Inputs.require(input.minIntervalHours()>=1 && input.minIntervalHours()<=720,"会员发券间隔应为1至720小时");
        return commands.run(actor,"coupon.delivery.create",key,input,View.class,()->{
            Inputs.require(input.deadline().isAfter(now()) && !input.deadline().isAfter(now().plus(Duration.ofDays(7))),"截止时间需在未来7天内");
            stores.requireActive(actor,input.storeId());audiences.requireFresh(actor.tenantId(),input.audience(),now());audiences.requireFresh(actor.tenantId(),input.audience(),input.deadline().minusMillis(1));
            coupons.validateExchange(actor.tenantId(),input.storeId(),input.definitionId(),input.definitionVersion(),now(),input.deadline());
            mapper.insert(actor.tenantId(),input,JsonCodec.write(input),now());return view(mapper.find(actor.tenantId(),input.batchId()));
        });
    }
    /** 管理列表稳定批次游标。 */
    public List<View> list(Actor actor,String store,String after,int limit){actor.requireAdmin();Inputs.page(after,limit);stores.requireActive(actor,store);return mapper.list(actor.tenantId(),store,after,limit).stream().map(this::view).toList();}
    /** 回执不会显示其他租户的人群信息。 */
    public List<Recipient> recipients(Actor actor,String id,String after,int limit){actor.requireAdmin();Identifiers.require(id);Inputs.page(after,limit);Inputs.found(mapper.find(actor.tenantId(),id));return mapper.recipients(actor.tenantId(),id,after,limit);}
    /** 撤销与停止分别建模，失败恢复保留原执行方向。 */
    public View control(Actor actor,String key,String id,Control input){
        actor.requireAdmin();Identifiers.require(id);Inputs.require(input!=null && input.expectedVersion()>=0 && input.action()!=null && Set.of("CANCEL","RETRY","REVOKE").contains(input.action()),"任务控制参数无效");Inputs.text(input.reason(),256);
        return commands.run(actor,"coupon.delivery.control",key,new Object[]{id,input},View.class,()->{
            var row=Inputs.found(mapper.lock(actor.tenantId(),id));if(row.version()!=input.expectedVersion())throw conflict("批次版本已变化");var content=content(row);String target,mode=row.mode();
            switch(input.action()) {
                case "CANCEL" -> {if(!Set.of("RUNNING","ISOLATED").contains(row.status()))throw conflict("当前批次不能取消");target="CANCELLED";}
                case "RETRY" -> {if(!row.status().equals("ISOLATED") || (mode.equals("ISSUE")&&!now().isBefore(content.deadline())))throw conflict("仅隔离且仍有效的发放可重试");target=mode.equals("REVOKE")?"REVOKING":"RUNNING";}
                case "REVOKE" -> {if(!Set.of("COMPLETED","CANCELLED","EXPIRED","ISOLATED").contains(row.status()))throw conflict("请先停止发放再撤销");mode="REVOKE";target="REVOKING";}
                default -> throw conflict("未知操作");
            }
            status(actor.tenantId(),id,row,target,mode);return view(mapper.lock(actor.tenantId(),id));
        });
    }
    /** 单轮上限20个收件人，正常推进与错误重试使用相同预算。 */
    public int pump(Actor actor){actor.requireAdmin();return pumpTenant(actor.tenantId());}
    /** 各租户有独立批次和频控，后台轮转4个租户。 */
    public synchronized int tick(){var tenants=mapper.tenants(tenantCursor,now());if(tenants.isEmpty()){tenantCursor="";return 0;}int count=0;for(var tenant:tenants)count+=pumpTenant(tenant);tenantCursor=tenants.getLast();return count;}
    private int pumpTenant(String tenant){
        String id=mapper.pending(tenant,now());if(id==null)return 0;int count=0;
        for(int i=0;i<20;i++){
            try{if(!Boolean.TRUE.equals(tx.execute(s->step(tenant,id))))break;count++;}
            catch(RuntimeException failure){String code=failure instanceof DomainException d?d.code().name():"STORAGE_FAILURE";tx.executeWithoutResult(s->{var row=mapper.lock(tenant,id);if(row!=null && Set.of("RUNNING","REVOKING").contains(row.status()))mapper.failed(tenant,id,code,now().plusSeconds((1L<<Math.min(row.attempts()+1,5))+java.util.concurrent.ThreadLocalRandom.current().nextInt(2)));});break;}
        }return count;
    }
    private boolean step(String tenant,String id){
        var row=mapper.lock(tenant,id);if(row==null || !Set.of("RUNNING","REVOKING").contains(row.status()) || row.availableAt().isAfter(now()))return false;
        var input=content(row);
        if(row.mode().equals("REVOKE")){
            var recipient=mapper.nextRevoke(tenant,id,row.revokeCursor());
            if(recipient==null){status(tenant,id,row,"REVOCATION_DONE","REVOKE");return false;}
            var result=coupons.revokeTargeted(tenant,recipient.memberId(),recipient.couponId(),source(id,recipient.memberId()));boolean revoked=result.equals("REVOKED");
            if(mapper.revokeResult(tenant,id,recipient.memberId(),revoked?"REVOKED":"KEPT",revoked?null:result)!=1)throw conflict("撤销回执并发变化");
            if(mapper.advanceRevoke(tenant,id,recipient.memberId(),revoked)!=1)throw conflict("撤销检查点写入冲突");return true;
        }
        if(!now().isBefore(input.deadline())){status(tenant,id,row,"EXPIRED","ISSUE");return false;}
        audiences.requireFresh(tenant,input.audience(),now());var next=audiences.members(tenant,input.audience(),row.cursorMember(),1);
        if(next.isEmpty()){status(tenant,id,row,"COMPLETED","ISSUE");return false;}
        String member=next.getFirst(),coupon=null,error=null;var subject=members.lockForOperation(tenant,member);
        if(subject==null || !subject.status().equals("ACTIVE"))error=subject==null?"MEMBER_MISSING":"MEMBER_INACTIVE";
        else {var due=mapper.frequency(tenant,member);if(due!=null && now().isBefore(due))error="FREQUENCY_LIMIT";}
        if(error==null){stores.requireActive(new Actor(tenant,"coupon-delivery",Actor.Role.ADMIN),input.storeId());coupon=coupons.grantTargeted(tenant,member,input.storeId(),source(id,member),input.definitionId(),input.definitionVersion()).couponId();mapper.frequencySet(tenant,member,now().plus(Duration.ofHours(input.minIntervalHours())));}
        mapper.recipient(tenant,id,new Recipient(member,error==null?"ISSUED":"SKIPPED",coupon,error,now()));if(mapper.advance(tenant,id,member,error==null)!=1)throw conflict("发券检查点写入冲突");return true;
    }
    private void status(String tenant,String id,CouponDeliveryMapper.Row row,String status,String mode){if(mapper.status(tenant,id,row.version(),status,mode,now())!=1)throw conflict("批次状态并发变化");}
    private String source(String id,String member){return JsonCodec.hash(id+"/"+member);}
    private Create content(CouponDeliveryMapper.Row row){return JsonCodec.read(row.contentJson(),Create.class);}
    private View view(CouponDeliveryMapper.Row row){return new View(content(row),row.status(),row.mode(),row.processed(),row.issued(),row.skipped(),row.revoked(),row.kept(),row.cursorMember(),row.revokeCursor(),row.attempts(),row.errorCode(),row.version());}
    private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MILLIS);}
    private DomainException conflict(String reason){return new DomainException(DomainException.Code.CONFLICT,reason);}
}
