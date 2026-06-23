package com.sstlfsj.rule.web.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** 业务异常：携带 HTTP 状态码与机器可读 errorCode，由 GlobalExceptionHandler 统一转为 ProblemDetail。 */
@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    /** @param status HTTP 状态码 @param errorCode 机器可读错误码 @param message 人类可读错误描述 */
    public ApiException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

}
