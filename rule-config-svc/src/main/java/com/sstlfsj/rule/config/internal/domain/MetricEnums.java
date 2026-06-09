package com.sstlfsj.rule.config.internal.domain;

import com.sstlfsj.rule.kernel.api.model.DataType;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** metric_definition 枚举列允许值（DB ENUM 去除后由 app 校验；单一真相源）。 */
public final class MetricEnums {
    // 由 DataType 派生，排除 UNKNOWN（运行时哨兵，非合法 metric 类型）；其余 8 个与原字面集逐元素相等
    public static final Set<String> DATA_TYPES = Arrays.stream(DataType.values())
            .filter(d -> d != DataType.UNKNOWN)
            .map(DataType::tag)
            .collect(Collectors.toUnmodifiableSet());
    public static final Set<String> SOURCE_TYPES =
            Set.of("ATTRIBUTE", "SQL_AGGREGATE", "EXTERNAL_HTTP", "STREAM");
    public static final Set<String> STATUSES = Set.of("ACTIVE", "DISABLED");
    private MetricEnums() {}
}
