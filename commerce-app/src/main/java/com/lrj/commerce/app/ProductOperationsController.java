package com.lrj.commerce.app;
import com.lrj.commerce.catalog.api.ProductOperationsApi;
import com.lrj.commerce.runtime.api.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 经营端与销售目录分离，商品服务在每次查询和变更时校验门店授权。 */
@RestController @RequestMapping("/v1/operations")
public class ProductOperationsController {
 private final ProductOperationsApi products;
 public ProductOperationsController(ProductOperationsApi products){this.products=products;}
 /** 新建商品主数据。 */
 @PostMapping("/products") public Object create(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@RequestBody ProductOperationsApi.ProductInput input){return products.create(actor,key,input);}
 /** 修订商品元资料。 */
 @PostMapping("/products/{id}") public Object changeProduct(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@RequestBody ProductOperationsApi.ProductChange input){return products.changeProduct(actor,key,id,input);}
 /** 门店商品主数据目录。 */
 @GetMapping("/products") public Object products(@AuthenticationPrincipal Actor actor,@RequestParam String storeId,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return products.products(actor,storeId,after,limit);}
 /** 新建不可换绑的规格。 */
 @PostMapping("/skus") public Object variant(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@RequestBody ProductOperationsApi.Variant input){return products.variant(actor,key,input);}
 /** 上下架和价格同步修订。 */
 @PostMapping("/skus/{id}") public Object change(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@RequestBody ProductOperationsApi.Change input){return products.change(actor,key,id,input);}
 /** 经营列表含下架SKU。 */
 @GetMapping("/skus") public Object skus(@AuthenticationPrincipal Actor actor,@RequestParam String storeId,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return products.skus(actor,storeId,after,limit);}
 /** 有界修订历史。 */
 @GetMapping("/skus/{id}/history") public Object history(@AuthenticationPrincipal Actor actor,@PathVariable String id,@RequestParam String storeId,@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="50") int limit){return products.history(actor,storeId,id,after,limit);}
}
