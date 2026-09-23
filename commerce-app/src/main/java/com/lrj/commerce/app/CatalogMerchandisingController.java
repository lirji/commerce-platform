package com.lrj.commerce.app;
import com.lrj.commerce.catalog.api.CatalogMerchandisingApi;
import com.lrj.commerce.runtime.api.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
/** HTTP只装配经营或销售查询，门店授权由目录应用服务执行。 */
@RestController @RequestMapping("/v1")
public class CatalogMerchandisingController {
 private final CatalogMerchandisingApi catalog;
 public CatalogMerchandisingController(CatalogMerchandisingApi catalog){this.catalog=catalog;}
 /** 结构化类目创建。 */
 @PostMapping("/operations/catalog-categories") public Object createCategory(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@RequestBody CatalogMerchandisingApi.CategoryInput input){return catalog.createCategory(actor,key,input);}
 /** 类目名称及停启用采用乐观版本。 */
 @PostMapping("/operations/catalog-categories/{id}") public Object categoryChange(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@RequestBody CatalogMerchandisingApi.CategoryChange input){return catalog.changeCategory(actor,key,id,input);}
 /** 管理类目目录含停用项。 */
 @GetMapping("/operations/catalog-categories") public Object categories(@AuthenticationPrincipal Actor actor,@RequestParam String storeId,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="100") int limit){return catalog.categories(actor,storeId,after,limit,false);}
 /** 销售筛选仅用当前有效类目。 */
 @GetMapping("/catalog/categories") public Object publicCategories(@AuthenticationPrincipal Actor actor,@RequestParam String storeId,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="100") int limit){return catalog.categories(actor,storeId,after,limit,true);}
 /** 不可变规格模板。 */
 @PostMapping("/operations/specification-templates") public Object createTemplate(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@RequestBody CatalogMerchandisingApi.Template input){return catalog.createTemplate(actor,key,input);}
 /** 有界返回每个模板最新版本。 */
 @GetMapping("/operations/specification-templates") public Object templates(@AuthenticationPrincipal Actor actor,@RequestParam String storeId,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="100") int limit){return catalog.templates(actor,storeId,after,limit);}
 /** SKU表单读取商品绑定的精确旧版本。 */
 @GetMapping("/operations/specification-templates/{id}/{version}") public Object template(@AuthenticationPrincipal Actor actor,@RequestParam String storeId,@PathVariable String id,@PathVariable long version){return catalog.template(actor,storeId,id,version);}
 /** 商品图文/类目/模板资料。 */
 @GetMapping("/operations/products/{id}/merchandising") public Object profile(@AuthenticationPrincipal Actor actor,@PathVariable String id,@RequestParam String storeId){return catalog.profile(actor,storeId,id);}
 /** 商品详情版本与售价版本独立。 */
 @PostMapping("/operations/products/{id}/merchandising") public Object profileChange(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@RequestBody CatalogMerchandisingApi.ProfileChange input){return catalog.changeProfile(actor,key,id,input);}
 /** 读取条码版本。 */
 @GetMapping("/operations/skus/{id}/barcode") public Object barcode(@AuthenticationPrincipal Actor actor,@PathVariable String id,@RequestParam String storeId){return catalog.barcode(actor,storeId,id);}
 /** 设置或清除经营条码。 */
 @PostMapping("/operations/skus/{id}/barcode") public Object barcodeChange(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@RequestBody CatalogMerchandisingApi.BarcodeChange input){return catalog.changeBarcode(actor,key,id,input);}
 /** 经营筛选可看下架规格，协议参数不会拼入SQL。 */
 @GetMapping("/operations/catalog-search") public Object operationsSearch(@AuthenticationPrincipal Actor actor,@RequestParam String storeId,@RequestParam(required=false) String q,@RequestParam(required=false) String categoryId,@RequestParam(required=false) String minimumPrice,@RequestParam(required=false) String maximumPrice,@RequestParam(required=false) String status,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return catalog.search(actor,new CatalogMerchandisingApi.Search(storeId,q,categoryId,minimumPrice,maximumPrice,status,after,limit),true);}
 /** 会员检索强制当前可售状态。 */
 @GetMapping("/catalog/search") public Object publicSearch(@AuthenticationPrincipal Actor actor,@RequestParam String storeId,@RequestParam(required=false) String q,@RequestParam(required=false) String categoryId,@RequestParam(required=false) String minimumPrice,@RequestParam(required=false) String maximumPrice,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return catalog.search(actor,new CatalogMerchandisingApi.Search(storeId,q,categoryId,minimumPrice,maximumPrice,null,after,limit),false);}
 /** 本店可售规格详情。 */
 @GetMapping("/catalog/items/{id}") public Object item(@AuthenticationPrincipal Actor actor,@PathVariable String id,@RequestParam String storeId){return catalog.item(actor,storeId,id);}
}
