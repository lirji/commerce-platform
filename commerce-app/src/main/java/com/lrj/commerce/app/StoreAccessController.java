package com.lrj.commerce.app;
import com.lrj.commerce.store.api.StoreAccessApi;
import com.lrj.commerce.runtime.api.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 平台授权与运营资源查询分离，路由和用例均执行权限检查。 */
@RestController @RequestMapping("/v1")
public class StoreAccessController {
 private final StoreAccessApi access;
 public StoreAccessController(StoreAccessApi access){this.access=access;}
 /** 新建有原因的资源授权。 */
 @PostMapping("/admin/store-grants") public Object create(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@RequestBody StoreAccessApi.Create input){return access.create(actor,key,input);}
 /** 版本化撤销或恢复。 */
 @PostMapping("/admin/store-grants/{id}/status") public Object change(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@RequestBody StoreAccessApi.Change input){return access.change(actor,key,id,input);}
 /** 授权审查列表。 */
 @GetMapping("/admin/store-grants") public Object list(@AuthenticationPrincipal Actor actor,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return access.list(actor,after,limit);}
 /** 运营仅见被授权资源。 */
 @GetMapping("/operations/stores") public Object stores(@AuthenticationPrincipal Actor actor,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return access.stores(actor,after,limit);}
}
