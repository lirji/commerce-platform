package com.lrj.commerce.app.iam;

import com.lrj.authz.sdk.*;
import com.lrj.commerce.app.http.store.StoreAccessController;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

/** 用例二次校验失败也保持商城错误契约，不让Filter之后的异常变成成功或泄露堆栈。 */
@RestControllerAdvice(assignableTypes = StoreAccessController.class)
@Order(-100)
public class CentralStoreErrors {
    /** 明确拒绝与上游不可用分开。 */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> denied() { return response(403, "FORBIDDEN"); }
    /** 依赖故障不触发旧权限回退。 */
    @ExceptionHandler(CentralAccessException.class)
    public ResponseEntity<?> unavailable(CentralAccessException failure) { return response(failure.status(), failure.status() == org.springframework.http.HttpStatus.UNAUTHORIZED.value() ? "UNAUTHENTICATED" : "UNAVAILABLE"); }
    private ResponseEntity<?> response(int status, String code) { return ResponseEntity.status(status).body(Map.of("code", code, "message", code, "traceId", UUID.randomUUID().toString())); }
}
