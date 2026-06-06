package com.sstlfsj.rule.web.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import jakarta.servlet.http.HttpServletRequest;

/** 全局异常处理器：将常见异常映射为 ApiResponse 错误格式。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 参数校验失败（@Valid 触发）→ 400。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .findFirst().orElse("请求参数无效");
        return ApiResponse.error("INVALID_ARGUMENT", msg);
    }

    /** 缺少必填 @RequestParam → 400。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleMissingParam(MissingServletRequestParameterException ex) {
        return ApiResponse.error("INVALID_ARGUMENT", ex.getParameterName() + " 参数必填");
    }

    /** 业务层主动抛出的非法参数 → 400。 */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException ex) {
        return ApiResponse.error("INVALID_ARGUMENT", ex.getMessage());
    }

    /** 无映射路径（含静态资源未命中）→ 404，而非被兜底成 500。 */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNotFound(NoResourceFoundException ex) {
        return ApiResponse.error("NOT_FOUND", "接口不存在: " + ex.getResourcePath());
    }

    /** 兜底：未预期异常 → 500；日志带请求方法/路径/query 便于定位。 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleGeneral(Exception ex, HttpServletRequest request) {
        String query = request.getQueryString();
        log.error("未预期异常 [{} {}{}]", request.getMethod(), request.getRequestURI(),
                query != null ? "?" + query : "", ex);
        return ApiResponse.error("INTERNAL_ERROR", "服务内部错误，请稍后重试");
    }
}
