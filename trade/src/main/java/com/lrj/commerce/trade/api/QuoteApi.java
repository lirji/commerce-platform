package com.lrj.commerce.trade.api;
import com.lrj.commerce.runtime.api.Actor;
import com.lrj.commerce.marketing.api.DecisionModels;
import java.time.Instant;
import java.util.List;

/** 交易报价拥有完整快照；不向客户端接受价格或人群事实。 */
public interface QuoteApi {
    record Selection(String skuId,int quantity) { }
    record Request(String storeId,List<Selection> items) { }
    record Line(String skuId,long revision,String title,int quantity,String unitPrice,String gross,String discount,String payable) { }
    record View(String quoteId,String memberId,String merchantId,String storeId,String currency,String gross,String discount,String payable,
                Instant createdAt,Instant expiresAt,List<Line> items,DecisionModels.Selection campaign,List<DecisionModels.Trace> trace) {
        public View { items=List.copyOf(items);trace=List.copyOf(trace); }
    }
    View create(Actor actor,String key,Request input);
    View read(Actor actor,String id);
}
