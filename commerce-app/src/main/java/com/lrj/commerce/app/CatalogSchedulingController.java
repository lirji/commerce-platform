package com.lrj.commerce.app;
import com.lrj.commerce.catalog.api.*;
import com.lrj.commerce.runtime.api.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
/** 协议层不接收租户或执行身份，业务服务再次校验经营权限。 */
@RestController @RequestMapping("/v1/operations")
public class CatalogSchedulingController {
 private final CatalogJobApi jobs;private final ChannelPriceApi prices;
 public CatalogSchedulingController(CatalogJobApi jobs,ChannelPriceApi prices){this.jobs=jobs;this.prices=prices;}
 /** 固定批量经营计划。 */
 @PostMapping("/catalog-jobs") public Object create(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@RequestBody CatalogJobApi.Create input){return jobs.create(actor,key,input);}
 /** 门店批次列表。 */
 @GetMapping("/catalog-jobs") public Object list(@AuthenticationPrincipal Actor actor,@RequestParam String storeId,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return jobs.list(actor,storeId,after,limit);}
 /** 有界逐项回执。 */
 @GetMapping("/catalog-jobs/{id}/items") public Object items(@AuthenticationPrincipal Actor actor,@PathVariable String id,@RequestParam String storeId,@RequestParam(defaultValue="0") int after,@RequestParam(defaultValue="100") int limit){return jobs.items(actor,storeId,id,after,limit);}
 /** 取消或从隔离位置恢复。 */
 @PostMapping("/catalog-jobs/{id}/control") public Object control(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@RequestBody CatalogJobApi.Control input){return jobs.control(actor,key,id,input);}
 /** 单轮推进，不把HTTP连接当长任务。 */
 @PostMapping("/catalog-jobs/pump") public Object pump(@AuthenticationPrincipal Actor actor,@RequestParam String storeId){return jobs.pump(actor,storeId);}
 /** 当前渠道配置。 */
 @GetMapping("/skus/{id}/channel-prices") public Object prices(@AuthenticationPrincipal Actor actor,@PathVariable String id,@RequestParam String storeId){return prices.list(actor,storeId,id);}
 /** 设置有期限的渠道价格。 */
 @PostMapping("/skus/{id}/channel-prices") public Object price(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@RequestBody ChannelPriceApi.Change input){return prices.change(actor,key,id,input);}
 /** 历史不可变，只按版本追加。 */
 @GetMapping("/skus/{id}/channel-prices/{channel}/history") public Object history(@AuthenticationPrincipal Actor actor,@PathVariable String id,@PathVariable Actor.Channel channel,@RequestParam String storeId,@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="100") int limit){return prices.history(actor,storeId,id,channel,after,limit);}
}
