package com.lrj.commerce.kernel;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 首批限定人民币；金额边界拒绝精度损失，不让隐式舍入影响资金守恒。 */
public record Money(BigDecimal amount) implements Comparable<Money> {
    private static final BigDecimal MAX = new BigDecimal("999999999999.99");
    public static final Money ZERO = new Money(BigDecimal.ZERO);

    public Money {
        if (amount == null || amount.precision() > 18 || amount.scale() < -12 || amount.scale() > 6
            || amount.signum() < 0 || amount.compareTo(MAX) > 0) {
            throw new DomainException(DomainException.Code.INVALID_INPUT, "金额超出范围");
        }
        try {
            amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException error) {
            throw new DomainException(DomainException.Code.INVALID_INPUT, "金额不能丢失分以下精度");
        }
    }

    /** 币种为明确契约，扩展币种前须一起扩展精度和换算规则。 */
    public String currency() { return "CNY"; }

    /** 分摊用精确分数，范围保证 long 转换无损。 */
    public long minorUnits() { return amount.movePointRight(2).longValueExact(); }

    /** 内部计算也经过相同金额约束。 */
    public static Money minor(long minorUnits) { return new Money(BigDecimal.valueOf(minorUnits, 2)); }

    /** 汇总时重新检查上限。 */
    public Money add(Money other) { return new Money(amount.add(other.amount)); }

    /** 不允许产生负应付或负分摊。 */
    public Money subtract(Money other) { return new Money(amount.subtract(other.amount)); }

    /** 件数由购物行约束，金额乘积仍需检查范围。 */
    public Money multiply(int count) { return new Money(amount.multiply(BigDecimal.valueOf(count))); }

    @Override
    public int compareTo(Money other) { return amount.compareTo(other.amount); }
}
