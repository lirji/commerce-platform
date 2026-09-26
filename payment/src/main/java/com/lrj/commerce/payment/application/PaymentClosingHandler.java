package com.lrj.commerce.payment.application;
import com.lrj.commerce.ordering.api.OrderApi;
import com.lrj.commerce.payment.infrastructure.PaymentMapper;
import com.lrj.commerce.runtime.JsonCodec;
import com.lrj.commerce.runtime.api.EventHandler;
import org.springframework.stereotype.Component;
import java.util.Set;
/** 订单进入CLOSING时重新安排渠道核对，自动重查次数已用尽的未知支付也能由后台关闭或确认。 */
@Component
public class PaymentClosingHandler implements EventHandler {
    private final PaymentMapper mapper;
    public PaymentClosingHandler(PaymentMapper mapper){this.mapper=mapper;}
    public String consumer(){return "payment-order-closing-v1";}
    public Set<String> types(){return Set.of("order.closing.v1");}
    /** 只重置核对计划，不改变支付或订单状态；终态尝试不受影响。 */
    public void handle(Event event){mapper.rearmCheck(event.tenantId(),JsonCodec.read(event.payloadJson(),OrderApi.View.class).orderId());}
}
