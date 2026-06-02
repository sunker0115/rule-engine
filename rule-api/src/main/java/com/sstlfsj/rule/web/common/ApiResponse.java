package com.sstlfsj.rule.web.common;

/** HTTP 统一响应包装，骨架层不含分页等扩展字段。 */
public record ApiResponse<T>(boolean success, T data, String errorCode, String message) {

    /** @param data 响应数据 @return 成功响应 */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, null);
    }

    /** @param errorCode 错误码 @param message 错误描述 @return 失败响应 */
    public static <T> ApiResponse<T> error(String errorCode, String message) {
        return new ApiResponse<>(false, null, errorCode, message);
    }
}
