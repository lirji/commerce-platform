package com.lrj.commerce.marketing.api;

/** 交易领域只消费此契约；不能直接取得营销规则仓储或引擎内部对象。 */
public interface DecisionPort {
    /** 相同版本、事实、时间和购物行得到相同结果，无 IO 副作用。 */
    DecisionModels.Quote decide(DecisionModels.Request request);
}
