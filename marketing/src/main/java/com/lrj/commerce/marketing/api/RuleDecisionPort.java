package com.lrj.commerce.marketing.api;
import java.util.Map;
/** 只计算有界可信事实，不承担授权或产生发放副作用。 */
public interface RuleDecisionPort {
    Condition.Truth evaluate(Condition condition,Map<String,Fact> facts);
}
