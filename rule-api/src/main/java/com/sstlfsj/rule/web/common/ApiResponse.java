package com.sstlfsj.rule.web.common;

import org.slf4j.MDC;

/** HTTP 统一响应包装；traceId 取自当前 MDC（与日志同源），便于按响应回溯链路日志。 */
public record ApiResponse<T>(boolean success, T data, String errorCode, String message, String traceId) {

    /** @param data 响应数据 @return 成功响应（含当前 traceId） */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, null, currentTraceId());
    }

    /** @param errorCode 错误码 @param message 错误描述 @return 失败响应（含当前 traceId） */
    public static <T> ApiResponse<T> error(String errorCode, String message) {
        return new ApiResponse<>(false, null, errorCode, message, currentTraceId());
    }

    /** 读取当前请求线程 MDC 中的 traceId，无追踪上下文时返回 null。 */
    private static String currentTraceId() {
        return MDC.get("traceId");
    }
}
