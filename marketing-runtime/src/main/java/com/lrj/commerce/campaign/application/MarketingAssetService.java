package com.lrj.commerce.campaign.application;
import com.lrj.commerce.campaign.api.*;
import com.lrj.commerce.campaign.infrastructure.AssetMapper;
import com.lrj.commerce.runtime.*;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
/** 可信快照失效后资格未知；旧规则版本永不原位修改。 */
@Service
public class MarketingAssetService implements MarketingAssets {
    private final AssetMapper mapper;private final Commands commands;private final Clock clock;
    public MarketingAssetService(AssetMapper mapper,Commands commands,Clock clock){this.mapper=mapper;this.commands=commands;this.clock=clock;}
    public AudienceView createAudience(Actor actor,String key,Audience input){
        actor.requireAdmin();Inputs.require(input!=null&&input.memberIds()!=null&&input.memberIds().size()<=500,"人群单批最多500会员");Identifiers.require(input.audienceId());Inputs.text(input.name(),128);Inputs.text(input.source(),128);Inputs.require(input.version()>0&&input.watermark()!=null&&input.validUntil()!=null,"人群版本或时间缺失");
        Inputs.require(!input.watermark().isAfter(clock.instant())&&input.validUntil().isAfter(input.watermark())&&Duration.between(input.watermark(),input.validUntil()).compareTo(Duration.ofHours(24))<=0,"人群快照新鲜度窗口无效");
        input.memberIds().forEach(Identifiers::require);Inputs.require(new HashSet<>(input.memberIds()).size()==input.memberIds().size(),"快照成员重复");
        return commands.run(actor,"audience.create",key,input,AudienceView.class,()->{mapper.audience(actor.tenantId(),input,input.memberIds().size());if(!input.memberIds().isEmpty())mapper.members(actor.tenantId(),input);return mapper.audienceFind(actor.tenantId(),input.audienceId(),input.version());});
    }
    public List<AudienceView> audiences(Actor actor,String after,int limit){actor.requireAdmin();Inputs.page(after,limit);return mapper.audiences(actor.tenantId(),after,limit);}
    /** 一次批量读取所有固定版本，缺失版本拒绝而非默认命中。 */
    public List<Source> sources(String tenant,String member,List<Ref> refs,Instant now){
        if(refs.isEmpty())return List.of();Inputs.require(refs.size()<=100,"人群引用数量超限");var unique=refs.stream().distinct().toList();var rows=mapper.sources(tenant,member,unique);if(rows.size()!=unique.size())throw new DomainException(DomainException.Code.CONFLICT,"活动引用人群版本缺失");
        return rows.stream().map(r->new Source(r.audienceId(),r.version(),r.source(),r.watermark(),r.validUntil(),now.isBefore(r.watermark())||!now.isBefore(r.validUntil())?"UNKNOWN":r.matched()?"HIT":"MISS")).toList();
    }
    public void requireFresh(String tenant,Ref ref,Instant now){validate(ref);var row=Inputs.found(mapper.audienceFind(tenant,ref.id(),ref.version()));if(now.isBefore(row.watermark())||!now.isBefore(row.validUntil()))throw new DomainException(DomainException.Code.CONFLICT,"发布引用人群快照已失效");}
    public RuleView createRule(Actor actor,String key,Rule input){actor.requireAdmin();Inputs.require(input!=null&&input.rule()!=null&&input.version()>0,"规则资产无效");Identifiers.require(input.ruleId());Inputs.text(input.name(),128);input.rule().requireTrustedFields();return commands.run(actor,"rule.create",key,input,RuleView.class,()->{mapper.rule(actor.tenantId(),input,JsonCodec.write(input.rule()));return view(mapper.ruleFind(actor.tenantId(),input.ruleId(),input.version()));});}
    public RuleView publishRule(Actor actor,String key,String id,long version){actor.requireAdmin();Identifiers.require(id);Inputs.require(version>0,"规则版本无效");return commands.run(actor,"rule.publish",key,List.of(id,version),RuleView.class,()->{var row=Inputs.found(mapper.ruleFind(actor.tenantId(),id,version));if(!row.status().equals("PUBLISHED")&&mapper.publishRule(actor.tenantId(),id,version)!=1)throw new DomainException(DomainException.Code.CONFLICT,"规则版本状态冲突");return view(mapper.ruleFind(actor.tenantId(),id,version));});}
    public List<RuleView> rules(Actor actor,String after,int limit){actor.requireAdmin();Inputs.page(after,limit);return mapper.rules(actor.tenantId(),after,limit).stream().map(this::view).toList();}
    public RuleNode publishedRule(String tenant,Ref ref){validate(ref);var row=Inputs.found(mapper.ruleFind(tenant,ref.id(),ref.version()));if(!row.status().equals("PUBLISHED"))throw new DomainException(DomainException.Code.CONFLICT,"活动必须引用已发布规则");return JsonCodec.read(row.ruleJson(),RuleNode.class);}
    private void validate(Ref ref){Inputs.require(ref!=null&&ref.version()>0,"资产引用无效");Identifiers.require(ref.id());}
    private RuleView view(AssetMapper.RuleRow row){return new RuleView(new Rule(row.ruleId(),row.version(),row.name(),JsonCodec.read(row.ruleJson(),RuleNode.class)),row.status());}
}
