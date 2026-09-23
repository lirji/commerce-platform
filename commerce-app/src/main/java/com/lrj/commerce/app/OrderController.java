package com.lrj.commerce.app;
import com.lrj.commerce.runtime.api.Actor;
import com.lrj.commerce.ordering.api.OrderApi;
import com.lrj.commerce.inventory.api.InventoryApi;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 不暴露任意订单状态写入口，只开放合法业务命令。 */
@RestController @RequestMapping("/v1")
public class OrderController {
    private final OrderApi orders;private final InventoryApi inventory;
    public OrderController(OrderApi orders,InventoryApi inventory) {this.orders=orders;this.inventory=inventory;}
    /** 请求体没有价格/状态，防止客户端越过报价和生命周期。 */
    @PostMapping("/orders") public Object create(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@RequestBody OrderApi.Create input) {return orders.create(actor,key,input);}
    /** 订单查询由用例执行归属检查。 */
    @GetMapping("/orders/{id}") public Object read(@AuthenticationPrincipal Actor actor,@PathVariable String id) {return orders.read(actor,id);}
    /** 游标和页大小在用例里有上限。 */
    @GetMapping("/orders") public Object list(@AuthenticationPrincipal Actor actor,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit) {return orders.list(actor,after,limit);}
    /** 取消命令由状态机决定是否允许释放预占。 */
    @PostMapping("/orders/{id}/cancel") public Object cancel(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@PathVariable String id) {return orders.cancel(actor,key,id);}
    /** 库存增量需要管理员和幂等键，不能直接覆盖数量。 */
    @PostMapping("/admin/inventory/receipts") public Object receive(@AuthenticationPrincipal Actor actor,@RequestHeader("Idempotency-Key") String key,@RequestBody InventoryApi.Receipt input) {return inventory.receive(actor,key,input);}
    /** 查询只返回当前租户库存。 */
    @GetMapping("/admin/inventory") public Object inventory(@AuthenticationPrincipal Actor actor,@RequestParam String storeId,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit) {return inventory.list(actor,storeId,after,limit);}
}
