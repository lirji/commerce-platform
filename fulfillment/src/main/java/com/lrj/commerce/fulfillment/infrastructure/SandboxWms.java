package com.lrj.commerce.fulfillment.infrastructure;
import com.lrj.commerce.fulfillment.api.WmsPort;
import com.lrj.commerce.kernel.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
/** 隔离WMS由本平台管理员录入事实，必须显式开启；不表示已调用真实物流系统。 */
@Component
public class SandboxWms implements WmsPort {
    private final boolean enabled;
    public SandboxWms(@Value("${commerce.sandbox-enabled:false}") boolean enabled){this.enabled=enabled;}
    public Proof shipment(String tenant,String order,String trackingNo){check();return new Proof("SANDBOX_WMS",trackingNo);}
    public void delivered(String tenant,String order,String trackingNo){check();}
    private void check(){if(!enabled)throw new DomainException(DomainException.Code.UNAVAILABLE,"WMS尚未配置，沙箱默认关闭");}
}
