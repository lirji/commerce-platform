package com.lrj.commerce.app.iam;

import com.lrj.authz.sdk.*;
import com.lrj.commerce.app.http.store.StoreAccessController;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

/** 用例二次校验失败也保持商城错误契约，不让Filter之后的异常变成成功或泄露堆栈。 */
@RestControllerAdvice(assignableTypes = {StoreAccessController.class,com.lrj.commerce.app.http.store.CentralScopeController.class,
    com.lrj.commerce.app.http.catalog.product.ProductOperationsController.class,
    com.lrj.commerce.app.http.catalog.job.CatalogSchedulingController.class,
    com.lrj.commerce.app.http.catalog.merchandising.CatalogMerchandisingController.class,
    com.lrj.commerce.app.http.order.OrderController.class,
    com.lrj.commerce.app.http.order.InventoryActionsController.class,
    com.lrj.commerce.app.http.commerce.CommerceController.class})
@Order(-100)
public class CentralStoreErrors {
    /** 明确拒绝与上游不可用分开。 */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> denied() { return response(403, "FORBIDDEN"); }
    /** 依赖故障不触发旧权限回退。 */
    @ExceptionHandler(CentralAccessException.class)
    public ResponseEntity<?> unavailable(CentralAccessException failure) { return response(failure.status(), failure.status() == org.springframework.http.HttpStatus.UNAUTHORIZED.value() ? "UNAUTHENTICATED" : "UNAVAILABLE"); }
    /** 参数与范围类型错误不能被默认异常边界转成500。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalid(){return response(400,"INVALID_ARGUMENT");}
    /** 数据库并发冲突与依赖不可用分开。 */
    @ExceptionHandler({org.springframework.dao.DataIntegrityViolationException.class,org.springframework.dao.ConcurrencyFailureException.class})
    public ResponseEntity<?> conflict(){return response(409,"CONFLICT");}
    /** 数据库故障没有旧数据或旧授权回退。 */
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<?> database(){return response(503,"UNAVAILABLE");}
    private ResponseEntity<?> response(int status, String code) { return ResponseEntity.status(status).body(Map.of("code", code, "message", code, "traceId", UUID.randomUUID().toString())); }
}
