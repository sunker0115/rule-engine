package com.sstlfsj.rule.web.common;

/** HTTP 成功响应包装。错误响应统一走 RFC 9457 {@link org.springframework.http.ProblemDetail}。 */
public record ApiResponse<T>(boolean success, T data) {

    /** @param data 响应数据 @return 成功响应 */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data);
    }
}
