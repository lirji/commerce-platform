package com.lrj.commerce.app.http.store;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 固定SPA入口只提供静态壳，所有数据仍经过各自API认证；不使用任意路径回退。 */
@Controller
public class CentralPageController {
    /** 支持独立应用入口刷新及OIDC回调，URL不携带业务Token。 */
    @GetMapping({"/operations/journeys","/operations/journey-instances","/operations/journey-scans","/operations/marketing-effects","/operations/marketing-executions", "/operations/campaigns", "/operations/campaign-budgets", "/operations/audiences", "/operations/segments", "/operations/rules", "/operations/entitlement-definitions", "/operations/entitlements", "/operations/coupon-definitions", "/operations/point-offers", "/operations/member-points", "/operations/member-cycles", "/operations/member-cycle-benefits", "/operations/member-behavior", "/operations/member-tags", "/operations/member-growth", "/operations/members", "/operations/directory", "/operations/inventory", "/operations/catalog", "/operations/products","/collaboration/products","/iam/callback"})
    public String page(){return "forward:/index.html";}
}
