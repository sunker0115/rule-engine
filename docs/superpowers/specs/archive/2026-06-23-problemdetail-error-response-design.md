# API 错误响应标准化：ProblemDetail (RFC 9457)

**日期**：2026-06-23  
**状态**：已确认

## 背景

当前项目错误/成功响应共用 `ApiResponse<T>`，格式为：

```json
{"success": true/false, "data": ..., "errorCode": "...", "message": "...", "traceId": "..."}
```

问题：
- 不符合 RFC 9457（原 RFC 7807）HTTP API 错误响应标准
- `traceId` 放在 body 里，不是响应头的常规做法
- 成功/失败字段混在同一个 record 里，`errorCode`/`message` 在成功响应中恒为 null

## 目标

- **错误** → `ProblemDetail`（RFC 9457 标准格式，`Content-Type: application/problem+json`）
- **成功** → `ApiResponse<T>`（精简为 `success` + `data`）
- **traceId** → `X-Trace-Id` 响应头（不在 body 里）

## 错误响应格式

```
Content-Type: application/problem+json
X-Trace-Id: abc123def456

{
  "type": "about:blank",
  "title": "请求参数校验失败",
  "status": 400,
  "detail": "tenantCode: must not be blank",
  "instance": "/api/v1/rule/evaluate",
  "errorCode": "INVALID_ARGUMENT"
}
```

### 字段映射规则

| RFC 9457 字段 | 取值 | 说明 |
|---|---|---|
| `type` | `about:blank` | 不托管问题文档，用默认值 |
| `title` | 按异常类型固定 | 如"请求参数校验失败"、"资源不存在"、"服务内部错误" |
| `status` | HTTP 状态码 | 与响应状态码一致 |
| `detail` | 本次具体原因 | 如"tenantCode: must not be blank"，可随发生次数变化 |
| `instance` | 请求 URI | 如 `/api/v1/rule/evaluate` |
| `errorCode` | 机器可读错误码 | 扩展属性，如 `INVALID_ARGUMENT`、`NOT_FOUND`、`INTERNAL_ERROR` |

### 各异常类型映射

| 异常 | status | errorCode | title |
|---|---|---|---|
| MethodArgumentNotValidException | 400 | INVALID_ARGUMENT | 请求参数校验失败 |
| MissingServletRequestParameterException | 400 | INVALID_ARGUMENT | 缺少必填参数 |
| MethodArgumentTypeMismatchException | 400 | INVALID_ARGUMENT | 参数类型不合法 |
| IllegalArgumentException | 400 | INVALID_ARGUMENT | 非法参数 |
| ApiException（新增） | 可变 | 可变 | 业务错误 |
| HttpMessageNotReadableException | 400 | INVALID_ARGUMENT | 请求体格式错误 |
| NoResourceFoundException | 404 | NOT_FOUND | 接口不存在 |
| ResponseStatusException | 透传 | 按 status 推导 | 按 status 推导 |
| Exception（兜底） | 500 | INTERNAL_ERROR | 服务内部错误 |

## 成功响应格式（不变）

```json
{
  "success": true,
  "data": { ... }
}
```

`ApiResponse<T>` 精简后仅保留 `success` + `data`，去掉 `errorCode`、`message`、`traceId` 及 `error()` 工厂方法。

## traceId 方案

新建 `TraceIdFilter`（`@Order(0)`，在 ActorIdFilter 之前），从 SLF4J MDC 读取 OTel 注入的 `traceId`，写入 `X-Trace-Id` 响应头。无论成功/错误响应都带此头。

## 新增 ApiException

Controller 中需要直接返回业务错误时（如 422 冲突、404 资源不存在），不再调用 `ApiResponse.error()`，改为抛 `ApiException`：

```java
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String errorCode;
    // constructor(status, errorCode, message)
}
```

GlobalExceptionHandler 统一捕获并转为 ProblemDetail。

## 改动文件清单

| 文件 | 改动 |
|---|---|
| `ApiResponse.java` | 去 errorCode/message/traceId、error()、currentTraceId() |
| `GlobalExceptionHandler.java` | 全部 handler 返回 ProblemDetail，加 ApiException handler |
| `ApiException.java`（新增） | 带 HttpStatus + errorCode 的运行时异常 |
| `TraceIdFilter.java`（新增） | 从 MDC 读 traceId 写入 X-Trace-Id 响应头 |
| `RuleBundleController.java` | `ApiResponse.error(...)` → `throw new ApiException(...)` |
| `AuditController.java` | `ApiResponse.error(...)` → `throw new ApiException(...)` |
| `ApiResponseTest.java` | 去掉 errorCode/message/traceId 相关断言 |
| `GlobalExceptionHandlerTest.java` | ProblemDetail 格式断言适配 |

## 不做

- 不设 `spring.mvc.problemdetails.enabled=true`（全局开启会覆盖自定义 handler 的部分行为）
- 不创建 per-error-code 的 type URI（无文档站托管）
- 不引入 Zalando Problem 库（Spring Boot 4 内置 ProblemDetail 足够）
