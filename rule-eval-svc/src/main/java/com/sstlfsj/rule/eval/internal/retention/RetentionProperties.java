package com.sstlfsj.rule.eval.internal.retention;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 数据保留清理配置（eval-svc 取 session 相关子集；同 engine.rule.retention 前缀，各模块各绑子集）。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "engine.rule.retention")
public class RetentionProperties {

    /** 总开关（默认开）。 */
    private boolean enabled = true;
    /** evaluation_session 保留天数。 */
    private int evaluationSessionDays = 90;
    /** dry_run_session 保留天数。 */
    private int dryRunSessionDays = 7;
    /** action_execution 保留天数(跟随 evaluation_session 生命周期,默认 90)。 */
    private int actionExecutionDays = 90;
    /** 单批删除上限。 */
    private int batchSize = 1000;
}
