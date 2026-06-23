package com.sstlfsj.rule.web.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import jakarta.servlet.http.HttpServletRequest;

import java.net.URI;

/** 全局异常处理器：统一转为 RFC 9457 ProblemDetail 格式（Content-Type: application/problem+json）。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常 → 透传 status + errorCode。 */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApi(ApiException ex, HttpServletRequest request) {
        log.warn("业务异常 [{} {}]: {} {}", request.getMethod(), request.getRequestURI(),
                ex.getStatus().value(), ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        pd.setTitle("业务错误");
        pd.setProperty("errorCode", ex.getErrorCode());
        pd.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity.status(ex.getStatus()).body(pd);
    }

    /** 参数校验失败（@Valid 触发）→ 400。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .findFirst().orElse("请求参数无效");
        log.warn("参数校验失败 [{} {}]: {}", request.getMethod(), request.getRequestURI(), msg);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, msg);
        pd.setTitle("请求参数校验失败");
        pd.setProperty("errorCode", "INVALID_ARGUMENT");
        pd.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity.badRequest().body(pd);
    }

    /** 缺少必填 @RequestParam → 400。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ProblemDetail> handleMissingParam(MissingServletRequestParameterException ex, HttpServletRequest request) {
        log.warn("缺少必填参数 [{} {}]: {}", request.getMethod(), request.getRequestURI(), ex.getParameterName());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                ex.getParameterName() + " 参数必填");
        pd.setTitle("缺少必填参数");
        pd.setProperty("errorCode", "INVALID_ARGUMENT");
        pd.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity.badRequest().body(pd);
    }

    /** 路径/查询参数类型转换失败（如 Long tenantId/id 收到非数字）→ 400。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        log.warn("参数类型不匹配 [{} {}]: {}={}", request.getMethod(), request.getRequestURI(),
                ex.getName(), ex.getValue());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "参数 " + ex.getName() + " 类型不合法: " + ex.getValue());
        pd.setTitle("参数类型不合法");
        pd.setProperty("errorCode", "INVALID_ARGUMENT");
        pd.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity.badRequest().body(pd);
    }

    /** ResponseStatusException → 透传 HTTP 状态码。 */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleResponseStatus(ResponseStatusException ex, HttpServletRequest request) {
        log.warn("响应状态异常 [{} {}]: {} {}", request.getMethod(), request.getRequestURI(),
                ex.getStatusCode().value(), ex.getReason());
        boolean is404 = ex.getStatusCode().value() == 404;
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.getStatusCode(),
                ex.getReason() != null ? ex.getReason() : "");
        pd.setTitle(is404 ? "资源不存在" : "请求错误");
        pd.setProperty("errorCode", is404 ? "NOT_FOUND" : "INVALID_ARGUMENT");
        pd.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity.status(ex.getStatusCode()).body(pd);
    }

    /** 业务层主动抛出的非法参数 → 400。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        log.warn("非法参数 [{} {}]: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setTitle("非法参数");
        pd.setProperty("errorCode", "INVALID_ARGUMENT");
        pd.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity.badRequest().body(pd);
    }

    /** 无映射路径（含静态资源未命中）→ 404。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(NoResourceFoundException ex, HttpServletRequest request) {
        log.warn("接口不存在 [{} {}]", request.getMethod(), request.getRequestURI());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                "接口不存在: " + ex.getResourcePath());
        pd.setTitle("接口不存在");
        pd.setProperty("errorCode", "NOT_FOUND");
        pd.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(pd);
    }

    /** 请求体反序列化失败（typed 绑定失败）→ 400。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.warn("请求体不可读 [{} {}]", request.getMethod(), request.getRequestURI());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "请求体格式错误或字段类型不符");
        pd.setTitle("请求体格式错误");
        pd.setProperty("errorCode", "INVALID_ARGUMENT");
        pd.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity.badRequest().body(pd);
    }

    /** 兜底：未预期异常 → 500。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGeneral(Exception ex, HttpServletRequest request) {
        String query = request.getQueryString();
        log.error("未预期异常 [{} {}{}]", request.getMethod(), request.getRequestURI(),
                query != null ? "?" + query : "", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "服务内部错误，请稍后重试");
        pd.setTitle("服务内部错误");
        pd.setProperty("errorCode", "INTERNAL_ERROR");
        pd.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(pd);
    }
}
