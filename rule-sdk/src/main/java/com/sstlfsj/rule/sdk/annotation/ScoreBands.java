package com.sstlfsj.rule.sdk.annotation;

import java.lang.annotation.*;

/** @ScoreBand 的容器(@Repeatable 要求)。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ScoreBands {
    ScoreBand[] value();
}
