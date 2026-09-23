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
    private final com.lrj.commerce.member.api.MemberApi members;private final com.lrj.commerce.member.api.MemberGrowthApi memberGrowth;private final com.lrj.commerce.catalog.api.CatalogApi catalog;private final com.lrj.commerce.marketing.api.DecisionPort decisions;private final com.lrj.commerce.benefit.api.EntitlementApi entitlements;private final com.lrj.commerce.campaign.infrastructure.BudgetMapper budgets;private final MarketingAssets assets;private final java.time.Clock clock;private final CampaignMapper mapper; private final StoreApi stores; private final Commands commands;
    public CampaignService(CampaignMapper mapper,StoreApi stores,Commands commands,MarketingAssets assets,java.time.Clock clock,com.lrj.commerce.campaign.infrastructure.BudgetMapper budgets,com.lrj.commerce.benefit.api.EntitlementApi entitlements,com.lrj.commerce.member.api.MemberApi members,com.lrj.commerce.member.api.MemberGrowthApi memberGrowth,com.lrj.commerce.catalog.api.CatalogApi catalog,com.lrj.commerce.marketing.api.DecisionPort decisions) {this.members=members;this.memberGrowth=memberGrowth;this.catalog=catalog;this.decisions=decisions;this.entitlements=entitlements;this.budgets=budgets;this.assets=assets;this.clock=clock;this.mapper=mapper;this.stores=stores;this.commands=commands;}
    /** 草稿内容不可变，修改必须创建新版本。 */
    public View create(Actor actor,String key,Draft input) {
        actor.requireAdmin();Inputs.require(input!=null,"请求不能为空");Identifiers.require(input.campaignId());Inputs.text(input.name(),128);
        Inputs.require(input.version()>0&&input.validFrom()!=null&&input.validTo()!=null&&input.validFrom().isBefore(input.validTo())&&(input.rule()!=null||(input.policy()!=null&&input.policy().rule()!=null)),"活动版本无效");
        if(input.rule()!=null)input.rule().requireTrustedFields();
        if(input.policy()!=null)Inputs.require(input.policy().audience()!=null||input.policy().rule()!=null||(input.policy().terms()!=null&&input.policy().terms().pricing()!=null),"受治理活动需引用可信资产或声明精细价格策略");
        if(input.policy()!=null&&input.policy().terms()!=null){var terms=input.policy().terms();pricing(terms.pricing());Inputs.require(terms.percentageBps()>=0&&terms.percentageBps()<=10000&&terms.platformFundingBps()>=0&&terms.platformFundingBps()<=10000,"活动百分比参数无效");if(terms.budget()!=null)Inputs.require(money(terms.budget()).compareTo(Money.ZERO)>0,"活动预算必须大于零");}
        money(input.minimumSpend()); Inputs.require(money(input.discountAmount()).compareTo(Money.ZERO)>0,"优惠必须大于零");
        return commands.run(actor,"campaign.create",key,input,View.class,()->{
            var store=stores.requireActive(actor,input.storeId());
            RuleNode rule=input.rule();
            if(input.policy()!=null){
                if(input.policy().rule()!=null)rule=assets.publishedRule(actor.tenantId(),input.policy().rule());
                if(input.policy().terms()!=null&&input.policy().terms().grant()!=null)entitlements.validateBinding(actor.tenantId(),input.storeId(),input.policy().terms().grant(),input.validFrom(),input.validTo());
                if(input.policy().audience()!=null)assets.requireFresh(actor.tenantId(),input.policy().audience(),clock.instant());
            }
            mapper.insert(actor.tenantId(),store.merchantId(),input,JsonCodec.write(rule),input.policy()==null?null:JsonCodec.write(input.policy()));
            budgets.create(actor.tenantId(),java.util.UUID.randomUUID().toString(),input.campaignId(),input.version(),input.policy()==null||input.policy().terms()==null?null:input.policy().terms().budget());
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
            if(publish&&row.policyJson()!=null){
                if(!java.util.Set.of("APPROVED","PAUSED").contains(row.status()))throw new DomainException(DomainException.Code.CONFLICT,"受治理活动必须审批后发布");
                var policy=JsonCodec.read(row.policyJson(),Policy.class);if(policy.audience()!=null)assets.requireFresh(actor.tenantId(),policy.audience(),clock.instant());
                if(policy.rule()!=null)assets.publishedRule(actor.tenantId(),policy.rule());
                if(policy.terms()!=null&&policy.terms().grant()!=null)entitlements.validateBinding(actor.tenantId(),row.storeId(),policy.terms().grant(),row.validFrom(),row.validTo());
            }
            stores.requireActive(actor,row.storeId());
            if(publish) mapper.pauseOthers(actor.tenantId(),id);
            if(mapper.change(actor.tenantId(),id,version,expected,publish?"PUBLISHED":"PAUSED")!=1)
                throw new DomainException(DomainException.Code.CONFLICT,"活动并发版本冲突");
            return view(mapper.find(actor.tenantId(),id,version));
        });
    }
    /** 管理台按活动ID列出每个活动最新内容版本。 */
    public List<View> list(Actor actor,String after,int limit) {actor.requireAdmin();Inputs.page(after,limit);return mapper.list(actor.tenantId(),after,limit).stream().map(this::view).toList();}
    private View view(CampaignMapper.Row r) {return new View(new Draft(r.campaignId(),r.version(),r.storeId(),r.name(),r.validFrom(),r.validTo(),r.minimumSpend(),r.discountAmount(),JsonCodec.read(r.ruleJson(),RuleNode.class),r.policyJson()==null?null:JsonCodec.read(r.policyJson(),Policy.class)),r.merchantId(),r.status(),r.lockVersion());}
    private Money money(String value) {Inputs.text(value,32);try{return new Money(new BigDecimal(value));}catch(NumberFormatException ex){throw new DomainException(DomainException.Code.INVALID_INPUT,"金额格式无效");}}
    /** 每次审批变更都带预期版本并写命令审计，不能跳级发布。 */
    public View review(Actor actor,String key,String id,long version,long expected,String action){
        actor.requireAdmin();Identifiers.require(id);Inputs.require(version>0&&expected>=0&&java.util.Set.of("submit","approve","reject").contains(action),"审批动作无效");
        return commands.run(actor,"campaign."+action,key,List.of(id,version,expected),View.class,()->{
            var row=mapper.lockGroup(actor.tenantId(),id).stream().filter(r->r.version()==version).findFirst().orElseThrow(()->new DomainException(DomainException.Code.NOT_FOUND,"活动版本不存在"));
            String required=action.equals("submit")?"DRAFT":"IN_REVIEW";String next=action.equals("submit")?"IN_REVIEW":action.equals("approve")?"APPROVED":"REJECTED";
            if(row.policyJson()==null||!row.status().equals(required)||row.lockVersion()!=expected)throw new DomainException(DomainException.Code.CONFLICT,"审批状态或版本冲突");
            if(mapper.change(actor.tenantId(),id,version,expected,next)!=1)throw new DomainException(DomainException.Code.CONFLICT,"活动审批并发冲突");return view(mapper.find(actor.tenantId(),id,version));
        });
    }
    /** 人群资格只影响对应活动，MISS/UNKNOWN不会被活动内部NOT反转。 */
    public Candidates candidates(Actor actor,String storeId,String memberId,java.time.Instant now){
        return candidates(actor.tenantId(),memberId,mapper.published(actor.tenantId(),storeId),now);
    }
    private Candidates candidates(String tenant,String memberId,List<CampaignMapper.Row> rows,java.time.Instant now){
        if(rows.size()>100)throw new DomainException(DomainException.Code.LIMIT_EXCEEDED,"活动候选超过上限");
        var refs=rows.stream().filter(r->r.policyJson()!=null).map(r->JsonCodec.read(r.policyJson(),Policy.class).audience()).filter(java.util.Objects::nonNull).distinct().toList();
        var sources=assets.sources(tenant,memberId,refs,now);var indexed=new java.util.HashMap<MarketingAssets.Ref,MarketingAssets.Source>();for(var source:sources)indexed.put(new MarketingAssets.Ref(source.audienceId(),source.version()),source);
        var offers=rows.stream().map(r->{
            com.lrj.commerce.marketing.api.Condition condition=JsonCodec.read(r.ruleJson(),RuleNode.class).toCondition();
            var policy=r.policyJson()==null?null:JsonCodec.read(r.policyJson(),Policy.class);
            if(policy!=null&&policy.audience()!=null){String match=indexed.get(policy.audience()).match();if(!match.equals("HIT"))condition=new com.lrj.commerce.marketing.api.Condition.Literal(match.equals("MISS")?com.lrj.commerce.marketing.api.Condition.Truth.NO_MATCH:com.lrj.commerce.marketing.api.Condition.Truth.UNKNOWN);}
            return new Offer(new Scope(tenant,r.merchantId(),r.storeId()),r.campaignId(),r.version(),r.validFrom(),r.validTo(),money(r.minimumSpend()),money(r.discountAmount()),condition,policy==null||policy.terms()==null?0:policy.terms().percentageBps(),policy==null||policy.terms()==null?null:pricing(policy.terms().pricing()));
        }).toList();return new Candidates(offers,sources);
    }
    /** 预览真实会员和目录价格，但不存报价、不预占库存/预算、不发权益。 */
    public PreviewResult preview(Actor actor,String id,long version,Preview input){
        actor.requireAdmin();Identifiers.require(id);Inputs.require(version>0&&input!=null&&input.items()!=null&&!input.items().isEmpty()&&input.items().size()<=100,"预览购物清单无效");
        var row=Inputs.found(mapper.find(actor.tenantId(),id,version));members.requireActive(actor,input.memberId());stores.requireActive(actor,row.storeId());
        var quantities=new java.util.TreeMap<String,Integer>();for(var item:input.items()){Inputs.require(item!=null&&item.quantity()>0&&item.quantity()<=10000,"购买数量无效");Identifiers.require(item.skuId());int count=quantities.getOrDefault(item.skuId(),0)+item.quantity();Inputs.require(count<=10000,"合并购买数量超限");quantities.put(item.skuId(),count);}
        var skus=catalog.published(actor,row.storeId(),new java.util.ArrayList<>(quantities.keySet()));
        var lines=skus.stream().map(sku->new Line(sku.skuId(),sku.skuId(),money(sku.unitPrice()),quantities.get(sku.skuId()))).toList();
        var at=input.at()==null?clock.instant():input.at();var candidates=candidates(actor.tenantId(),input.memberId(),List.of(row),at);
        Money gross=lines.stream().map(line->line.unitPrice().multiply(line.quantity())).reduce(Money.ZERO,Money::add);
        var result=decisions.decide(new Request(new Scope(actor.tenantId(),row.merchantId(),row.storeId()),input.memberId(),at,lines,MemberRuleFacts.from(memberGrowth.facts(actor.tenantId(),input.memberId()),gross.amount().toPlainString()),candidates.offers()));
        return new PreviewResult(result.gross().amount().toPlainString(),result.discount().amount().toPlainString(),result.payable().amount().toPlainString(),result.lines().stream().map(line->new PreviewLine(line.skuId(),line.gross().amount().toPlainString(),line.discount().amount().toPlainString(),line.payable().amount().toPlainString())).toList(),result.trace(),candidates.sources(),"仅模拟该活动版本；不含优惠券，不预占库存或预算，实际下单再次校验。模拟时间不回溯会员事实。");
    }
    private com.lrj.commerce.marketing.api.DecisionModels.Pricing pricing(CampaignApi.Pricing input){
        if(input==null)return null;
        Inputs.require(input.tiers()!=null&&input.tiers().size()<=8,"阶梯数量无效");
        return new com.lrj.commerce.marketing.api.DecisionModels.Pricing(input.includedSkuIds(),input.excludedSkuIds(),input.tiers().stream().map(tier->{Inputs.require(tier!=null,"阶梯不能为空");return new com.lrj.commerce.marketing.api.DecisionModels.Tier(money(tier.minimumSpend()),money(tier.discountAmount()),tier.percentageBps());}).toList());
    }

}
