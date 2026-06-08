package com.sstlfsj.rule.config.internal.domain;

import java.util.Set;

/** metric_definition 枚举列允许值（DB ENUM 去除后由 app 校验；单一真相源）。 */
public final class MetricEnums {
    public static final Set<String> DATA_TYPES =
            Set.of("LONG", "DOUBLE", "DECIMAL", "STRING", "BOOLEAN", "LIST", "DATE", "DATETIME");
    public static final Set<String> SOURCE_TYPES =
            Set.of("ATTRIBUTE", "SQL_AGGREGATE", "EXTERNAL_HTTP", "STREAM");
    public static final Set<String> STATUSES = Set.of("ACTIVE", "DISABLED");
    private MetricEnums() {}
}
