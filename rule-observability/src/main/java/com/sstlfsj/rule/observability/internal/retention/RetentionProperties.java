package com.sstlfsj.rule.observability.internal.retention;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 数据保留清理配置（observability 取 trace 相关子集；同 engine.rule.retention 前缀，各模块各绑子集）。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "engine.rule.retention")
public class RetentionProperties {

    /** 总开关（默认开）。 */
    private boolean enabled = true;
    /** node_trace 保留天数。 */
    private int nodeTraceDays = 30;
    /** 单批删除上限。 */
    private int batchSize = 1000;
}
