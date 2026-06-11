package com.sstlfsj.rule.kernel.api.annotation;

import java.lang.annotation.*;

/** 标注 MetricSourceHandler 实现类的指标来源类型标识与元数据。 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MetricSourceType {
    /** 指标来源类型标识,全局唯一;= metric 的 {@code sourceType} 路由键(如 "SQL_AGGREGATE"、"EXTERNAL_HTTP")。 */
    String value();
    /** 参数 schema(JSON 字符串),供前端渲染 metric 配置参数与发布期校验;默认空对象表示无参数。 */
    String paramsSchema() default "{}";
}
