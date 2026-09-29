package com.lrj.commerce.app.http.store;
import com.lrj.commerce.app.iam.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

/** 前端只能传检索、随机游标和任务命令，不能提交ScopePlan、Owner事实或SQL。 */
@RestController
@ConditionalOnProperty(name={"commerce.iam.store-read.enabled","commerce.iam.scope.enabled"},havingValue="true")
@RequestMapping("/v1/operations/scoped/{type}")
public class CentralScopeController {
    private final CentralScopeService scopes;
    private final com.lrj.commerce.catalog.product.api.ScopedProductOperations products;
    /** 所有真实权限检查属于中央用例，不依赖按钮显示。 */
    public CentralScopeController(CentralScopeService scopes,com.lrj.commerce.catalog.product.api.ScopedProductOperations products){this.scopes=scopes;this.products=products;}
    /** 返回精确授权范围内的数据和计数，下一页只能使用本次服务端游标。 */
    @GetMapping
    public Object page(@AuthenticationPrincipal CentralStoreIdentity identity,@PathVariable String type,@RequestParam(defaultValue="") String cursor,@RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="") String search){return scopes.page(identity,type,cursor,limit,search);}
    /** 资源事实由后端读库生成，浏览器仅指定资源主键。 */
    @GetMapping("/resources/{id}")
    public Object detail(@AuthenticationPrincipal CentralStoreIdentity identity,@PathVariable String type,@PathVariable String id){return scopes.detail(identity,type,id);}
    /** 只允许商品试点，不把任意type动态映射到写操作。 */
    @GetMapping("/resources/{id}/actions")
    public Object actions(@AuthenticationPrincipal CentralStoreIdentity identity,@PathVariable String type,@PathVariable String id){product(type);return scopes.actions(identity,id);}
    /** 元资料修订与菜单提示独立授权；无storeId或权限表达式输入。 */
    @PostMapping("/resources/{id}")
    public Object change(@AuthenticationPrincipal CentralStoreIdentity identity,@PathVariable String type,@PathVariable String id,
            @RequestHeader("Idempotency-Key") String key,@RequestBody com.lrj.commerce.catalog.product.api.ScopedProductOperations.MetadataChange input){product(type);return scopes.changeProduct(identity,id,input,key,products);}
    private static void product(String type){if(!com.lrj.authz.protocol.ScopeDtos.PRODUCT_RESOURCE_TYPE.equals(type))throw new IllegalArgumentException("不支持的写入资源");}
    /** 独立导出能力提示，不能因可读就显示为可导出。 */
    @GetMapping("/export-access")
    public Object exportAccess(@AuthenticationPrincipal CentralStoreIdentity identity,@PathVariable String type){return scopes.exportAccess(identity,type);}
    /** 提交不等于开始或完成，任务不保存用户Token。 */
    @PostMapping("/exports")
    public ResponseEntity<Object> submit(@AuthenticationPrincipal CentralStoreIdentity identity,@PathVariable String type,@RequestParam(defaultValue="") String search,@RequestHeader("Idempotency-Key") String key){return ResponseEntity.accepted().body(scopes.submit(identity,type,search,key));}
    /** 排队完成后显式开始，重新检查成员与授权版本。 */
    @PostMapping("/exports/{id}/start")
    public Object start(@AuthenticationPrincipal CentralStoreIdentity identity,@PathVariable String type,@PathVariable String id,@RequestParam long version,@RequestHeader("Idempotency-Key") String key){return scopes.start(identity,type,id,version,key);}
    /** 每次最多50行；调用者用新返回版本推进下个检查点。 */
    @PostMapping("/exports/{id}/advance")
    public Object advance(@AuthenticationPrincipal CentralStoreIdentity identity,@PathVariable String type,@PathVariable String id,@RequestParam long version,@RequestHeader("Idempotency-Key") String key){return scopes.advance(identity,type,id,version,key);}
    /** 仅申请人可以读本租户自己的任务。 */
    @GetMapping("/exports/{id}")
    public Object job(@AuthenticationPrincipal CentralStoreIdentity identity,@PathVariable String type,@PathVariable String id){return scopes.job(identity,type,id);}
    /** 下载实时复查权限和资源版本，不提供公开地址。 */
    @GetMapping("/exports/{id}/download")
    public Object download(@AuthenticationPrincipal CentralStoreIdentity identity,@PathVariable String type,@PathVariable String id){return ResponseEntity.ok().header("Content-Disposition","attachment; filename=export.json").body(scopes.download(identity,type,id));}
}
