package com.lrj.commerce.app;
import com.lrj.commerce.runtime.api.Actor;
import com.lrj.commerce.ordering.api.OrderApi;
import com.lrj.commerce.payment.api.PaymentApi;
import com.lrj.commerce.store.api.StoreApi;
import org.springframework.boot.context.properties.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
/** 页面所需读取仍通过各领域API，不能为管理台绕过数据所有权。 */
@RestController @RequestMapping("/v1") @EnableConfigurationProperties(ConsoleController.Capabilities.class)
public class ConsoleController {
    /** 能力展示不是授权；沙箱操作端点继续验证实际渠道配置。 */
    @ConfigurationProperties("commerce")
    public record Capabilities(boolean sandboxEnabled,boolean workersEnabled) { }
    private final OrderApi orders;private final PaymentApi payments;private final StoreApi stores;private final Capabilities capabilities;
    public ConsoleController(OrderApi orders,PaymentApi payments,StoreApi stores,Capabilities capabilities){this.orders=orders;this.payments=payments;this.stores=stores;this.capabilities=capabilities;}
    /** 仅返回非敏感运行开关。 */
    @GetMapping("/runtime-capabilities") public Capabilities capabilities(){return capabilities;}
    /** 租户范围的会员店铺目录。 */
    @GetMapping("/stores") public Object stores(@AuthenticationPrincipal Actor actor,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return stores.browse(actor,after,limit);}
    /** 有界运营订单队列。 */
    @GetMapping("/admin/orders") public Object orders(@AuthenticationPrincipal Actor actor,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return orders.adminList(actor,after,limit);}
    /** 详情不包含收货地址。 */
    @GetMapping("/admin/orders/{id}") public Object order(@AuthenticationPrincipal Actor actor,@PathVariable String id){return orders.adminRead(actor,id);}
    /** 支付状态来自持久化尝试。 */
    @GetMapping("/admin/orders/{id}/payment") public Object payment(@AuthenticationPrincipal Actor actor,@PathVariable String id){return payments.adminRead(actor,id);}
    /** 管理主动核对与后台查询同语义。 */
    @PostMapping("/admin/orders/{id}/payment/reconcile") public Object reconcile(@AuthenticationPrincipal Actor actor,@PathVariable String id){return payments.adminReconcile(actor,id);}
}
