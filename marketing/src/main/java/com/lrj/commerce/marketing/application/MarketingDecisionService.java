package com.lrj.commerce.marketing.application;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.kernel.Money;
import com.lrj.commerce.marketing.api.DecisionPort;
import com.lrj.commerce.marketing.api.DecisionModels.*;
import com.lrj.commerce.marketing.domain.RuleEvaluator;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** 单活动优惠决策；资格计算和实际权益履约保持独立，避免报价时产生副作用。 */
public final class MarketingDecisionService implements DecisionPort {
    private final RuleEvaluator evaluator = new RuleEvaluator();

    /** 输入必须由可信上游组成；此处只保证值约束、作用域及确定性，不替代授权。 */
    @Override
    public Quote decide(Request request) {
        if (request == null) throw new DomainException(DomainException.Code.INVALID_INPUT, "请求不能为空");
        List<Line> lines = request.lines().stream().sorted(Comparator.comparing(Line::lineId)).toList();
        List<Offer> offers = request.offers().stream().sorted(Comparator.comparing(Offer::campaignId)).toList();
        var lineIds = new HashSet<String>();
        Money gross = Money.ZERO;
        for (Line line : lines) {
            if (!lineIds.add(line.lineId())) invalid("购物行标识重复");
            gross = gross.add(line.unitPrice().multiply(line.quantity()));
        }
        var offerIds = new HashSet<String>();
        // 全部候选先检查作用域；不能因已过期或不命中就吞掉错误租户的数据。
        for (Offer offer : offers) {
            if (!offerIds.add(offer.campaignId())) invalid("活动标识重复");
            if (!offer.scope().equals(request.scope())) {
                throw new DomainException(DomainException.Code.SCOPE_MISMATCH, "活动与请求作用域不同");
            }
        }
        var trace = new ArrayList<Trace>();
        Selection selected = null;
        Money discount = Money.ZERO;
        for (Offer offer : offers) {
            Reason reason = reason(request, offer, gross);
            trace.add(new Trace(offer.campaignId(), offer.version(), reason));
            if (reason != Reason.ELIGIBLE) continue;
            Money actual = offer.discount().compareTo(gross) > 0 ? gross : offer.discount();
            // 已按活动标识排序；仅严格更优时替换，保证平局结果与输入顺序无关。
            if (actual.compareTo(discount) > 0) {
                discount = actual;
                selected = new Selection(offer.campaignId(), offer.version());
            }
        }
        return new Quote(gross, discount, gross.subtract(discount), selected, allocate(lines, gross, discount), trace);
    }

    private Reason reason(Request request, Offer offer, Money gross) {
        if (request.at().isBefore(offer.from()) || !request.at().isBefore(offer.to())) return Reason.OUTSIDE_VALIDITY;
        if (gross.compareTo(offer.minimumSpend()) < 0) return Reason.BELOW_MINIMUM;
        return switch (evaluator.evaluate(offer.condition(), request.facts())) {
            case MATCH -> Reason.ELIGIBLE;
            case NO_MATCH -> Reason.CONDITION_NO_MATCH;
            case UNKNOWN -> Reason.CONDITION_UNKNOWN;
        };
    }

    /** 总优惠不能超过原金额，输入仍做完整有界校验再复用余数分摊。 */
    public List<PricedLine> allocate(List<Line> input,Money discount){
        if(input==null||input.isEmpty()||input.size()>100||input.stream().anyMatch(java.util.Objects::isNull)||discount==null)invalid("分摊输入无效");
        var lines=input.stream().sorted(Comparator.comparing(Line::lineId)).toList();var ids=new HashSet<String>();Money gross=Money.ZERO;
        for(var line:lines){if(!ids.add(line.lineId()))invalid("购物行标识重复");gross=gross.add(line.unitPrice().multiply(line.quantity()));}
        if(discount.compareTo(gross)>0)invalid("分摊优惠超过原金额");return allocate(lines,gross,discount);
    }
    private List<PricedLine> allocate(List<Line> lines, Money total, Money discount) {
        long[] cents = new long[lines.size()];
        BigInteger[] remainders = new BigInteger[lines.size()];
        long allocated = 0;
        for (int i = 0; i < lines.size(); i++) {
            long lineGross = lines.get(i).unitPrice().multiply(lines.get(i).quantity()).minorUnits();
            // 总金额的分数乘积可超过 long，必须先用 BigInteger 求商余数。
            BigInteger[] parts = total.minorUnits() == 0 ? new BigInteger[]{BigInteger.ZERO, BigInteger.ZERO}
                : BigInteger.valueOf(lineGross).multiply(BigInteger.valueOf(discount.minorUnits()))
                    .divideAndRemainder(BigInteger.valueOf(total.minorUnits()));
            cents[i] = parts[0].longValueExact();
            remainders[i] = parts[1];
            allocated += cents[i];
        }
        var ranking = new ArrayList<Integer>();
        for (int i = 0; i < lines.size(); i++) ranking.add(i);
        ranking.sort(Comparator.<Integer, BigInteger>comparing(i -> remainders[i]).reversed()
            .thenComparing(i -> lines.get(i).lineId()));
        long remaining = discount.minorUnits() - allocated;
        for (int i = 0; i < remaining; i++) cents[ranking.get(i)]++;
        var result = new ArrayList<PricedLine>();
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            Money gross = line.unitPrice().multiply(line.quantity());
            Money share = Money.minor(cents[i]);
            result.add(new PricedLine(line.lineId(), line.skuId(), gross, share, gross.subtract(share)));
        }
        return List.copyOf(result);
    }

    private static void invalid(String message) {
        throw new DomainException(DomainException.Code.INVALID_INPUT, message);
    }
}
