package com.lrj.commerce.app;
import com.lrj.commerce.ops.api.OpsPageApi;
import com.lrj.commerce.runtime.api.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
/** 低代码入口仍执行真实业务权限，预览与动作端点分离。 */
@RestController @RequestMapping("/v1/admin/ops-pages")
public class OpsPageController {
    private final OpsPageApi pages;
    public OpsPageController(OpsPageApi pages){this.pages=pages;}
    record Revision(long expectedVersion) { }
    /** 创建不可变页面版本。 */
    @PostMapping public Object create(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@RequestBody OpsPageApi.Definition input){return pages.create(actor,key,input);}
    /** 最新版本列表。 */
    @GetMapping public Object list(@AuthenticationPrincipal Actor actor,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return pages.list(actor,after,limit);}
    /** 真实数据只读预览，不持久化页面。 */
    @PostMapping("/preview") public Object preview(@AuthenticationPrincipal Actor actor,@RequestBody OpsPageApi.Definition input){return pages.preview(actor,input);}
    /** 发布版本渲染。 */
    @GetMapping("/{id}/render") public Object render(@AuthenticationPrincipal Actor actor,@PathVariable String id){return pages.render(actor,id);}
    /** 用于对比和回退的有界版本列表。 */
    @GetMapping("/{id}/versions") public Object versions(@AuthenticationPrincipal Actor actor,@PathVariable String id){return pages.versions(actor,id);}
    /** 审批、发布或回退只能按合法迁移执行。 */
    @PostMapping("/{id}/{version}/{action}") public Object change(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@PathVariable long version,@PathVariable String action,@RequestBody Revision input){return pages.change(actor,key,id,version,input.expectedVersion(),action);}
    /** 执行固定白名单中的已声明业务动作。 */
    @PostMapping("/{id}/{version}/actions/{action}") public Object execute(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@PathVariable long version,@PathVariable String action,@RequestBody OpsPageApi.ActionInput input){return pages.execute(actor,key,id,version,action,input);}
}
