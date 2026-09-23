package com.lrj.commerce.marketing.api;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.kernel.Identifiers;
import com.lrj.commerce.kernel.Money;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 决策的不可变输入输出；事实可信性由上层适配器负责，内核仍校验作用域。 */
public final class DecisionModels {
    private DecisionModels() { }
    public record Scope(String tenantId, String merchantId, String storeId) {
        public Scope { Identifiers.require(tenantId); Identifiers.require(merchantId); Identifiers.require(storeId); }
    }
    public record Line(String lineId, String skuId, Money unitPrice, int quantity) {
        public Line {
            Identifiers.require(lineId); Identifiers.require(skuId);
            require(unitPrice != null && quantity >= 1 && quantity <= 10000, "购物行无效");
            unitPrice.multiply(quantity);
        }
    }
    public record Tier(Money minimumSpend,Money discount,int percentageBps) {
        public Tier { require(minimumSpend!=null&&discount!=null&&discount.compareTo(Money.ZERO)>0&&percentageBps>=0&&percentageBps<=10000,"阶梯优惠无效"); }
    }
    public record Pricing(List<String> includedSkuIds,List<String> excludedSkuIds,List<Tier> tiers) {
        public Pricing {
            require(includedSkuIds!=null&&excludedSkuIds!=null&&tiers!=null&&includedSkuIds.size()<=100&&excludedSkuIds.size()<=100&&tiers.size()<=8,"商品范围或阶梯数量无效");
            includedSkuIds.forEach(Identifiers::require);excludedSkuIds.forEach(Identifiers::require);
            require(new java.util.HashSet<>(includedSkuIds).size()==includedSkuIds.size()&&new java.util.HashSet<>(excludedSkuIds).size()==excludedSkuIds.size(),"商品范围重复");
            Money prior=null;for(var tier:tiers){require(tier!=null&&(prior==null||tier.minimumSpend().compareTo(prior)>0),"阶梯门槛必须递增");prior=tier.minimumSpend();}
            includedSkuIds=List.copyOf(includedSkuIds);excludedSkuIds=List.copyOf(excludedSkuIds);tiers=List.copyOf(tiers);
        }
        public boolean includes(String sku){return (includedSkuIds.isEmpty()||includedSkuIds.contains(sku))&&!excludedSkuIds.contains(sku);}
    }
    public record Offer(Scope scope, String campaignId, long version, Instant from, Instant to,
                        Money minimumSpend, Money discount, Condition condition,int percentageBps,Pricing pricing) {
        /** 旧固定减免调用保持兼容；百分比在gross确定后按分计算。 */
        public Offer(Scope scope,String campaignId,long version,Instant from,Instant to,Money minimumSpend,Money discount,Condition condition){this(scope,campaignId,version,from,to,minimumSpend,discount,condition,0,null); }
        public Offer(Scope scope,String campaignId,long version,Instant from,Instant to,Money minimumSpend,Money discount,Condition condition,int percentageBps){this(scope,campaignId,version,from,to,minimumSpend,discount,condition,percentageBps,null); }
        public Offer {
            Identifiers.require(campaignId);
            require(scope != null && version > 0 && from != null && to != null && from.isBefore(to)
                && minimumSpend != null && discount != null && discount.compareTo(Money.ZERO) > 0
                && condition != null && percentageBps>=0 && percentageBps<=10000, "活动版本无效");
            Condition.validate(condition);
        }
    }
    public record Request(Scope scope, String memberId, Instant at, List<Line> lines,
                          Map<String, Fact> facts, List<Offer> offers) {
        public Request {
            Identifiers.require(memberId);
            require(scope != null && at != null && lines != null && facts != null && offers != null, "决策输入不完整");
            if (lines.isEmpty() || lines.size() > 100 || facts.size() > 64 || offers.size() > 100) {
                throw new DomainException(DomainException.Code.LIMIT_EXCEEDED, "决策输入数量超限");
            }
            require(lines.stream().noneMatch(java.util.Objects::isNull)
                && offers.stream().noneMatch(java.util.Objects::isNull)
                && facts.values().stream().noneMatch(java.util.Objects::isNull), "决策集合不能包含空值");
            facts.keySet().forEach(Identifiers::require);
            lines = List.copyOf(lines); facts = Map.copyOf(facts); offers = List.copyOf(offers);
        }
    }
    public enum Reason { OUTSIDE_VALIDITY, OUTSIDE_PRODUCT_SCOPE, BELOW_MINIMUM, CONDITION_NO_MATCH, CONDITION_UNKNOWN, ELIGIBLE }
    public record Trace(String campaignId, long version, Reason reason) { }
    public record PricedLine(String lineId, String skuId, Money gross, Money discount, Money payable) { }
    public record Selection(String campaignId, long version) { }
    public record Quote(Money gross, Money discount, Money payable, Selection selected,
                        List<PricedLine> lines, List<Trace> trace) {
        public Quote { lines = List.copyOf(lines); trace = List.copyOf(trace); }
    }

    private static void require(boolean valid, String reason) {
        if (!valid) throw new DomainException(DomainException.Code.INVALID_INPUT, reason);
    }
}
