package com.lrj.commerce.benefit.application;
import com.lrj.commerce.benefit.api.EntitlementApi;
import com.lrj.commerce.benefit.infrastructure.EntitlementMapper;
import com.lrj.commerce.member.api.MemberApi;
import com.lrj.commerce.store.api.StoreApi;
import com.lrj.commerce.runtime.*;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
/** 授予与核销以持久余额为权威；已消费权益退款时明确待补偿，不凭空追回。 */
@Service
public class EntitlementService implements EntitlementApi,EventHandler {
    private final EntitlementMapper mapper;private final MemberApi members;private final StoreApi stores;private final Commands commands;private final Outbox outbox;private final Clock clock;
    public EntitlementService(EntitlementMapper mapper,MemberApi members,StoreApi stores,Commands commands,Outbox outbox,Clock clock){this.mapper=mapper;this.members=members;this.stores=stores;this.commands=commands;this.outbox=outbox;this.clock=clock;}
    /** 权益窗口与单位有界，发行配额不是可变的页面配置。 */
    public DefinitionView create(Actor actor,String key,Definition input){actor.requireAdmin();Inputs.require(input!=null&&input.version()>0&&input.units()>0&&input.units()<=10000&&input.quota()>0&&input.quota()<=1000000&&input.validityDays()>0&&input.validityDays()<=365&&input.validFrom()!=null&&input.validTo()!=null&&input.validFrom().isBefore(input.validTo()),"权益定义无效");Identifiers.require(input.benefitId());Inputs.text(input.name(),128);return commands.run(actor,"entitlement.definition",key,input,DefinitionView.class,()->{stores.requireActive(actor,input.storeId());mapper.definition(actor.tenantId(),input);return definition(mapper.definitionFind(actor.tenantId(),input.benefitId(),input.version()));});}
    public List<DefinitionView> definitions(Actor actor,String store,String after,int limit){actor.requireAdmin();Identifiers.require(store);Inputs.page(after,limit);return mapper.definitions(actor.tenantId(),store,after,limit).stream().map(this::definition).toList();}
    /** 发布必须保证所承诺的权益定义覆盖整个活动窗口。 */
    public void validateBinding(String tenant,String store,Ref ref,Instant from,Instant to){validateRef(ref);var d=Inputs.found(mapper.definitionFind(tenant,ref.benefitId(),ref.version()));if(!d.storeId().equals(store)||from.isBefore(d.validFrom())||to.isAfter(d.validTo())||!clock.instant().isBefore(d.validTo()))throw conflict();}
    /** 下单最终预留额度，未付款时不生成可消费余额。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void reserveOrder(Actor actor,String order,String member,String store,Ref ref){if(ref==null)return;validateRef(ref);var d=Inputs.found(mapper.definitionFind(actor.tenantId(),ref.benefitId(),ref.version()));if(!d.storeId().equals(store)||clock.instant().isBefore(d.validFrom())||!clock.instant().isBefore(d.validTo())||mapper.reserveQuota(actor.tenantId(),ref.benefitId(),ref.version())!=1)throw conflict();mapper.grant(actor.tenantId(),UUID.randomUUID().toString(),order,member,d);}
    /** 付款只产生发放任务，真实余额由独立幂等消费者授予。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void confirmOrder(String tenant,String order){var grant=mapper.byOrder(tenant,order);if(grant==null)return;if(grant.status().equals(State.CANCELLED))throw conflict();if(!grant.status().equals(State.RESERVED))return;var d=Inputs.found(mapper.definitionFind(tenant,grant.benefitId(),grant.benefitVersion()));if(mapper.finishQuota(tenant,grant,true)!=1)throw conflict();Instant expires=clock.instant().plus(Duration.ofDays(d.validityDays()));change(tenant,grant,State.REQUESTED,0,0,expires);var requested=mapper.find(tenant,grant.grantId());outbox.append(tenant,"benefit.grant.requested.v1",grant.grantId(),requested.version(),Map.of("grantId",grant.grantId()));}
    @Transactional(propagation=Propagation.MANDATORY)
    public void releaseOrder(String tenant,String order){var grant=mapper.byOrder(tenant,order);if(grant==null||grant.status().equals(State.CANCELLED))return;if(!grant.status().equals(State.RESERVED)||mapper.finishQuota(tenant,grant,false)!=1)throw conflict();change(tenant,grant,State.CANCELLED,0,0,null);}
    /** 已消费单位留下待人工补偿，不产生负余额或伪造成功撤回。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void reverseOrder(String tenant,String order){var grant=mapper.byOrder(tenant,order);if(grant==null||Set.of(State.REVOKED,State.COMPENSATION_REQUIRED,State.COMPENSATED).contains(grant.status()))return;if(!Set.of(State.REQUESTED,State.AVAILABLE,State.CONSUMED).contains(grant.status()))throw conflict();int consumed=grant.status().equals(State.REQUESTED)?0:grant.units()-grant.remainingUnits();change(tenant,grant,consumed==0?State.REVOKED:State.COMPENSATION_REQUIRED,0,consumed,grant.expiresAt());entry(tenant,grant.grantId(),"REVOKE",grant.remainingUnits(),0,order);if(consumed>0)entry(tenant,grant.grantId(),"COMPENSATION_REQUIRED",consumed,0,order);}
    public List<View> wallet(Actor actor,String after,int limit){Inputs.page(after,limit);return mapper.list(actor.tenantId(),members.current(actor).memberId(),after,limit);}
    public List<View> adminList(Actor actor,String after,int limit){actor.requireAdmin();Inputs.page(after,limit);return mapper.list(actor.tenantId(),null,after,limit);}
    /** 账本详情也验证会员归属，不因知道grantId而越权。 */
    public List<Ledger> ledger(Actor actor,String id,String after,int limit){Identifiers.require(id);Inputs.page(after,limit);var grant=Inputs.found(mapper.find(actor.tenantId(),id));if(!grant.memberId().equals(members.current(actor).memberId()))throw new DomainException(DomainException.Code.NOT_FOUND,"权益不存在");return mapper.ledger(actor.tenantId(),id,after,limit);}
    /** 余额扣减与账本和命令回放同事务；并发核销不能超额。 */
    public View consume(Actor actor,String key,String id,Consume input){Identifiers.require(id);Inputs.require(input!=null&&input.units()>0&&input.units()<=10000,"核销单位无效");return commands.run(actor,"entitlement.consume",key,Map.of("id",id,"input",input),View.class,()->{var grant=Inputs.found(mapper.lockOwned(actor.tenantId(),members.current(actor).memberId(),id));if(mapper.consume(actor.tenantId(),grant,input.units())!=1)throw conflict();var updated=mapper.find(actor.tenantId(),id);entry(actor.tenantId(),id,"CONSUME",input.units(),updated.remainingUnits(),key);return updated;});}
    /** 人工处理仅记录已明确的恢复或损失决定，不自动执行外部扣款。 */
    public View resolve(Actor actor,String key,String id,Resolution input){actor.requireAdmin();Identifiers.require(id);Inputs.require(input!=null&&input.resolution()!=null&&Set.of("RECOVERED","WRITTEN_OFF").contains(input.resolution()),"补偿结论无效");Inputs.text(input.reference(),128);return commands.run(actor,"entitlement.resolve",key,Map.of("id",id,"input",input),View.class,()->{var grant=Inputs.found(mapper.lock(actor.tenantId(),id));if(!grant.status().equals(State.COMPENSATION_REQUIRED))throw conflict();change(actor.tenantId(),grant,State.COMPENSATED,0,0,grant.expiresAt());entry(actor.tenantId(),id,input.resolution(),grant.debtUnits(),0,input.reference());return mapper.find(actor.tenantId(),id);});}
    public String consumer(){return "internal-entitlement-grant-v1";}
    public Set<String> types(){return Set.of("benefit.grant.requested.v1");}
    /** 退款先到则状态已REVOKED，迟到的授予事件不能复活余额。 */
    public void handle(Event event){var grant=Inputs.found(mapper.lock(event.tenantId(),event.aggregateId()));if(grant.status().equals(State.RESERVED)||grant.status().equals(State.CANCELLED))throw conflict();if(!grant.status().equals(State.REQUESTED))return;if(grant.version()!=event.aggregateVersion())throw conflict();change(event.tenantId(),grant,State.AVAILABLE,grant.units(),0,grant.expiresAt());entry(event.tenantId(),grant.grantId(),"GRANT",grant.units(),grant.units(),grant.orderId());}
    private void change(String tenant,View grant,State status,int remaining,int debt,Instant expires){if(mapper.change(tenant,grant,status,remaining,debt,expires)!=1)throw conflict();}
    private void entry(String tenant,String grant,String action,int units,int balance,String reference){mapper.entry(tenant,UUID.randomUUID().toString(),grant,action,units,balance,reference);}
    private void validateRef(Ref ref){Inputs.require(ref!=null&&ref.version()>0,"权益引用无效");Identifiers.require(ref.benefitId());}
    private DefinitionView definition(EntitlementMapper.DefinitionRow d){return new DefinitionView(new Definition(d.benefitId(),d.version(),d.storeId(),d.name(),d.units(),d.quota(),d.validFrom(),d.validTo(),d.validityDays()),d.reserved(),d.issued());}
    private DomainException conflict(){return new DomainException(DomainException.Code.CONFLICT,"权益额度、余额、有效期或状态冲突");}
}
