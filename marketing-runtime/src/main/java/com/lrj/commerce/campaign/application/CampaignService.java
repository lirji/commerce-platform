package com.lrj.commerce.campaign.application;
import com.lrj.commerce.campaign.api.*;
import com.lrj.commerce.campaign.infrastructure.CampaignMapper;
import com.lrj.commerce.marketing.api.DecisionModels.*;
import com.lrj.commerce.store.api.StoreApi;
import com.lrj.commerce.runtime.*;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.kernel.*;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;

/** 发布切换与审计同事务；旧版本不会在切换中和新版本同时生效。 */
@Service
public class CampaignService implements CampaignApi {
    private final CampaignMapper mapper; private final StoreApi stores; private final Commands commands;
    public CampaignService(CampaignMapper mapper,StoreApi stores,Commands commands) {this.mapper=mapper;this.stores=stores;this.commands=commands;}
    /** 草稿内容不可变，修改必须创建新版本。 */
    public View create(Actor actor,String key,Draft input) {
        actor.requireAdmin();Inputs.require(input!=null,"请求不能为空");Identifiers.require(input.campaignId());Inputs.text(input.name(),128);
        Inputs.require(input.version()>0&&input.validFrom()!=null&&input.validTo()!=null&&input.validFrom().isBefore(input.validTo())&&input.rule()!=null,"活动版本无效");
        input.rule().toCondition();
        money(input.minimumSpend()); Inputs.require(money(input.discountAmount()).compareTo(Money.ZERO)>0,"优惠必须大于零");
        return commands.run(actor,"campaign.create",key,input,View.class,()->{
            var store=stores.requireActive(actor,input.storeId());
            mapper.insert(actor.tenantId(),store.merchantId(),input,JsonCodec.write(input.rule()));
            return view(mapper.find(actor.tenantId(),input.campaignId(),input.version()));
        });
    }
    /** 发布前锁定同活动全部版本，唯一约束继续承担最终完整性。 */
    public View publish(Actor actor,String key,String id,long version,long expected) {return change(actor,key,id,version,expected,true);}
    /** 暂停只影响新报价，历史报价快照不重写。 */
    public View pause(Actor actor,String key,String id,long version,long expected) {return change(actor,key,id,version,expected,false);}
    private View change(Actor actor,String key,String id,long version,long expected,boolean publish) {
        actor.requireAdmin();Identifiers.require(id);Inputs.require(version>0&&expected>=0,"活动版本无效");
        return commands.run(actor,publish?"campaign.publish":"campaign.pause",key,List.of(id,version,expected),View.class,()->{
            var group=mapper.lockGroup(actor.tenantId(),id);
            var row=group.stream().filter(r->r.version()==version).findFirst().orElseThrow(()->new DomainException(DomainException.Code.NOT_FOUND,"活动版本不存在"));
            if(row.lockVersion()!=expected || (publish&&row.status().equals("PUBLISHED")) || (!publish&&!row.status().equals("PUBLISHED")))
                throw new DomainException(DomainException.Code.CONFLICT,"活动状态或版本冲突");
            stores.requireActive(actor,row.storeId());
            if(publish) mapper.pauseOthers(actor.tenantId(),id);
            if(mapper.change(actor.tenantId(),id,version,expected,publish?"PUBLISHED":"PAUSED")!=1)
                throw new DomainException(DomainException.Code.CONFLICT,"活动并发版本冲突");
            return view(mapper.find(actor.tenantId(),id,version));
        });
    }
    /** 管理台按活动ID列出每个活动最新内容版本。 */
    public List<View> list(Actor actor,String after,int limit) {actor.requireAdmin();Inputs.page(after,limit);return mapper.list(actor.tenantId(),after,limit).stream().map(this::view).toList();}
    /** 不静默截断候选，以免活动多时悄悄改变报价。 */
    public List<Offer> published(Actor actor,String storeId) {
        var rows=mapper.published(actor.tenantId(),storeId);
        if(rows.size()>100) throw new DomainException(DomainException.Code.LIMIT_EXCEEDED,"活动候选超过上限");
        return rows.stream().map(r->new Offer(new Scope(actor.tenantId(),r.merchantId(),r.storeId()),r.campaignId(),r.version(),r.validFrom(),r.validTo(),money(r.minimumSpend()),money(r.discountAmount()),JsonCodec.read(r.ruleJson(),RuleNode.class).toCondition())).toList();
    }
    private View view(CampaignMapper.Row r) {return new View(new Draft(r.campaignId(),r.version(),r.storeId(),r.name(),r.validFrom(),r.validTo(),r.minimumSpend(),r.discountAmount(),JsonCodec.read(r.ruleJson(),RuleNode.class)),r.merchantId(),r.status(),r.lockVersion());}
    private Money money(String value) {Inputs.text(value,32);try{return new Money(new BigDecimal(value));}catch(NumberFormatException ex){throw new DomainException(DomainException.Code.INVALID_INPUT,"金额格式无效");}}
}
