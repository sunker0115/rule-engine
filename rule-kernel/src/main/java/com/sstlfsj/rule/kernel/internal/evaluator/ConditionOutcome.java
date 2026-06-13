package com.sstlfsj.rule.kernel.internal.evaluator;

import com.sstlfsj.rule.kernel.api.model.EvalErrorCode;

/**
 * 条件求值三态：满足 / 不满足 / 不可判定（取数失败或无算子，携带 errorCode）。
 * 叶子结果额外携带 resolvedValue/valueSource，供 trace 展示实际值与来源；
 * 容器组合（And/Or/Not）结果无叶子值，两者均为 null。
 * errorCode 字段保持 String 以容纳 provider 开放码（如 metric source 抛错码）。
 */
record ConditionOutcome(Status status, String errorCode, Object resolvedValue, String valueSource) {

    /** 三态枚举。 */
    enum Status { SATISFIED, NOT_SATISFIED, ERROR }

    static final ConditionOutcome SATISFIED = new ConditionOutcome(Status.SATISFIED, null, null, null);
    static final ConditionOutcome NOT_SATISFIED = new ConditionOutcome(Status.NOT_SATISFIED, null, null, null);

    /** 容器组合结果（And/Or/Not），无叶子值。 */
    static ConditionOutcome of(boolean satisfied) {
        return satisfied ? SATISFIED : NOT_SATISFIED;
    }

    /** 不可判定（无叶子值，如容器层 NO_EVALUATOR）；provider 开放码经此原样穿透。 */
    static ConditionOutcome error(String errorCode) {
        return new ConditionOutcome(Status.ERROR, errorCode, null, null);
    }

    /** 不可判定 + 规范错误码（无叶子值）。errorCode 以 {@link EvalErrorCode#name()} 落 String。 */
    static ConditionOutcome error(EvalErrorCode errorCode) {
        return error(errorCode.name());
    }

    /** 叶子求值结果，携带实际值/来源。 */
    static ConditionOutcome leaf(boolean satisfied, Object resolvedValue, String valueSource) {
        return new ConditionOutcome(satisfied ? Status.SATISFIED : Status.NOT_SATISFIED, null, resolvedValue, valueSource);
    }

    /** 叶子取数失败，携带来源；provider 开放码经此原样穿透。 */
    static ConditionOutcome error(String errorCode, Object resolvedValue, String valueSource) {
        return new ConditionOutcome(Status.ERROR, errorCode, resolvedValue, valueSource);
    }

    /** 叶子取数失败 + 规范错误码，携带来源。errorCode 以 {@link EvalErrorCode#name()} 落 String。 */
    static ConditionOutcome error(EvalErrorCode errorCode, Object resolvedValue, String valueSource) {
        return error(errorCode.name(), resolvedValue, valueSource);
    }

    boolean satisfied() { return status == Status.SATISFIED; }
    boolean isError()   { return status == Status.ERROR; }
}
