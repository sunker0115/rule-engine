package com.sstlfsj.rule.sdk.annotation;

import java.lang.annotation.*;

/** 评分分档:score ≥ min 时归入 decision;多档取满足条件中 min 最大的一档。decision 须是 @RuleDef.decisions 之一。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Repeatable(ScoreBands.class)
public @interface ScoreBand {
    /** 下界(含)。 */
    double min();
    /** 命中该档的决策码。 */
    String decision();
}
