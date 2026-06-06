package com.sstlfsj.rule.kernel.internal.evaluator;

/** 条件求值三态：满足 / 不满足 / 不可判定（取数失败或无算子，携带 errorCode）。 */
record ConditionOutcome(Status status, String errorCode) {

    /** 三态枚举。 */
    enum Status { SATISFIED, NOT_SATISFIED, ERROR }

    static final ConditionOutcome SATISFIED = new ConditionOutcome(Status.SATISFIED, null);
    static final ConditionOutcome NOT_SATISFIED = new ConditionOutcome(Status.NOT_SATISFIED, null);

    /** 由布尔结果构造满足/不满足。 */
    static ConditionOutcome of(boolean satisfied) {
        return satisfied ? SATISFIED : NOT_SATISFIED;
    }

    /** 构造不可判定结果。 */
    static ConditionOutcome error(String errorCode) {
        return new ConditionOutcome(Status.ERROR, errorCode);
    }

    boolean satisfied() { return status == Status.SATISFIED; }
    boolean isError()   { return status == Status.ERROR; }
}
