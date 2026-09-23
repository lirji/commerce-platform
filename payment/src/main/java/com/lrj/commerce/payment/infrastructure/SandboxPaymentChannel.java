package com.lrj.commerce.payment.infrastructure;
import com.lrj.commerce.payment.api.*;
import com.lrj.commerce.runtime.api.Inputs;
import com.lrj.commerce.kernel.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
/** 仅隔离开发使用的数据库渠道账本；默认关闭，不能冒充银行或三方支付。 */
@Component
public class SandboxPaymentChannel implements PaymentChannel {
    private final PaymentMapper mapper;private final boolean enabled;
    public SandboxPaymentChannel(PaymentMapper mapper,@Value("${commerce.sandbox-enabled:false}") boolean enabled){this.mapper=mapper;this.enabled=enabled;}
    public String provider(){check();return "SANDBOX";}
    /** 幂等创建渠道请求，崩溃后的核对可以安全重试。 */
    @Transactional(propagation=Propagation.NEVER)
    public void ensure(String tenant,PaymentApi.View payment){check();mapper.ensureChannel(tenant,payment);}
    /** 查询只读可信账本，不接受浏览器的收款断言。 */
    @Transactional(propagation=Propagation.NEVER)
    public Evidence observe(String tenant,String id){check();return Inputs.found(mapper.channel(tenant,id));}
    /** 条件关单与成功互斥；未知结果不能当作已关闭。 */
    @Transactional(propagation=Propagation.NEVER)
    public Evidence close(String tenant,String id){check();mapper.channelClose(tenant,id);return Inputs.found(mapper.channel(tenant,id));}
    private void check(){if(!enabled)throw new DomainException(DomainException.Code.UNAVAILABLE,"尚未配置支付渠道；沙箱默认关闭");}
}
