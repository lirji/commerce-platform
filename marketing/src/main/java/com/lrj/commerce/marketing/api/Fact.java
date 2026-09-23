package com.lrj.commerce.marketing.api;

import com.lrj.commerce.kernel.DomainException;
import java.math.BigDecimal;

/** 可信快照中的受限值，禁止以任意对象或脚本扩展规则执行能力。 */
public sealed interface Fact permits Fact.Decimal, Fact.Text, Fact.Tags {
    /** 精确集合成员判断，避免把字符串子串匹配当成标签资格。 */
    record Tags(java.util.Set<String> values) implements Fact {
        public Tags {
            if(values==null||values.size()>64)throw new DomainException(DomainException.Code.LIMIT_EXCEEDED,"标签事实数量超限");
            values.forEach(com.lrj.commerce.kernel.Identifiers::require);values=java.util.Set.copyOf(values);
        }
    }
    record Decimal(BigDecimal value) implements Fact {
        public Decimal {
            if (value == null || value.precision() > 18 || value.scale() < -6 || value.scale() > 6) {
                throw new DomainException(DomainException.Code.INVALID_INPUT, "数字事实精度超限");
            }
        }
    }
    record Text(String value) implements Fact {
        public Text {
            if (value == null || value.length() > 256) {
                throw new DomainException(DomainException.Code.INVALID_INPUT, "文本事实长度超限");
            }
        }
    }
}
