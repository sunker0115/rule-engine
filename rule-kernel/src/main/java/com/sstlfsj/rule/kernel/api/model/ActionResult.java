package com.sstlfsj.rule.kernel.api.model;

/** Action 执行结果，由 ActionHandler 返回，不要在 Handler 中抛异常。 */
public record ActionResult(
        String actionId,
        String actionType,
        ActionStatus status,
        String errorCode,
        String errorMessage,
        boolean retryable
) {
    /** Action 执行状态枚举。 */
    public enum ActionStatus { SUCCESS, FAILED, SKIPPED }

    public static ActionResult success(String actionId, String actionType) {
        return new ActionResult(actionId, actionType, ActionStatus.SUCCESS, null, null, false);
    }

    public static ActionResult skipped(String actionId, String actionType, String reason) {
        return new ActionResult(actionId, actionType, ActionStatus.SKIPPED, reason, null, false);
    }

    public static ActionResult failed(String actionId, String actionType,
                                      String errorCode, boolean retryable) {
        return new ActionResult(actionId, actionType, ActionStatus.FAILED,
                errorCode, null, retryable);
    }

    /** 补偿操作不支持时的标准返回值。 */
    public static ActionResult notSupported() {
        return new ActionResult(null, null, ActionStatus.SKIPPED,
                "COMPENSATE_NOT_SUPPORTED", null, false);
    }
}
