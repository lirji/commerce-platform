package com.lrj.commerce.payment.infrastructure;
import com.lrj.commerce.payment.api.*;
import com.lrj.commerce.runtime.api.Inputs;
import com.lrj.commerce.kernel.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
/** 持久沙箱退款账本；正式退款适配另行接入，不会默认成功。 */
@Component
public class SandboxRefundChannel implements RefundChannel {
    private final RefundMapper mapper;private final boolean enabled;
    public SandboxRefundChannel(RefundMapper mapper,@Value("${commerce.sandbox-enabled:false}") boolean enabled){this.mapper=mapper;this.enabled=enabled;}
    @Transactional(propagation=Propagation.NEVER)
    public void ensure(String tenant,RefundApi.View refund){check();mapper.ensureChannel(tenant,refund);}
    @Transactional(propagation=Propagation.NEVER)
    public Evidence observe(String tenant,String refundId){check();return Inputs.found(mapper.channel(tenant,refundId));}
    private void check(){if(!enabled)throw new DomainException(DomainException.Code.UNAVAILABLE,"退款渠道尚未配置，沙箱默认关闭");}
}
