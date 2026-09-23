package com.lrj.commerce.app;
import com.lrj.commerce.kernel.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.converter.HttpMessageNotReadableException;

/** 边界统一错误语义，不将SQL/堆栈或内部对象暴露给客户端。 */
@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(DomainException.class)
    ResponseEntity<?> domain(DomainException ex,HttpServletRequest req) {
        int status=switch(ex.code()) {
            case FORBIDDEN -> 403; case NOT_FOUND -> 404;
            case CONFLICT,IDEMPOTENCY_CONFLICT,ILLEGAL_TRANSITION -> 409;
            case UNAVAILABLE -> 503; default -> 400;
        };
        return response(status,ex.code().name(),ex.getMessage(),req);
    }
    @ExceptionHandler({DataIntegrityViolationException.class,ConcurrencyFailureException.class})
    ResponseEntity<?> conflict(Exception ex,HttpServletRequest req) {return response(409,"CONFLICT","资源已存在或并发状态冲突",req);}
    @ExceptionHandler({HttpMessageNotReadableException.class,org.springframework.web.bind.MissingRequestHeaderException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,org.springframework.web.bind.MissingServletRequestParameterException.class})
    ResponseEntity<?> invalid(Exception ex,HttpServletRequest req) {return response(400,"INVALID_INPUT","请求格式无效",req);}
    /** 未注册的资源与错误HTTP方法不是系统故障，不误报为500。 */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<?> missing(Exception ex,HttpServletRequest req){return response(404,"NOT_FOUND","资源不存在",req);}
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<?> method(Exception ex,HttpServletRequest req){return response(405,"METHOD_NOT_ALLOWED","请求方法不支持",req);}
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception ex,HttpServletRequest req) {
        org.slf4j.LoggerFactory.getLogger(ApiErrors.class).error("request failed traceId={} errorType={}",SecurityConfiguration.trace(req),ex.getClass().getSimpleName());
        return response(500,"INTERNAL_ERROR","系统暂时无法完成请求",req);
    }
    private ResponseEntity<?> response(int status,String code,String message,HttpServletRequest request) {
        return ResponseEntity.status(status).body(Map.of("code",code,"message",message,"traceId",SecurityConfiguration.trace(request)));
    }
}
