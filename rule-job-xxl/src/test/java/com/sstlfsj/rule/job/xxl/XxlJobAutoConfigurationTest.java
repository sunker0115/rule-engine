package com.sstlfsj.rule.job.xxl;

import com.sstlfsj.rule.job.xxl.internal.XxlJobSchedulerAdapter;
import com.sstlfsj.rule.kernel.api.spi.scheduler.Scheduler;
import com.sstlfsj.rule.kernel.api.spi.scheduler.TaskRunCallback;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证装配 gate：仅 engine.rule.job.scheduler=xxl-job 时提供 Scheduler。 */
class XxlJobAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
            .withBean(TaskRunCallback.class, () -> taskId -> {})
            .withConfiguration(AutoConfigurations.of(XxlJobAutoConfiguration.class));

    @Test
    void notActiveByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(Scheduler.class));
    }

    @Test
    void activeWhenSchedulerIsXxlJob() {
        runner.withPropertyValues(
                        "engine.rule.job.scheduler=xxl-job",
                        "engine.rule.job.xxl.enabled=false",
                        "engine.rule.job.xxl.admin-addresses=http://127.0.0.1:1/xxl-job-admin")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(Scheduler.class);
                    assertThat(ctx.getBean(Scheduler.class)).isInstanceOf(XxlJobSchedulerAdapter.class);
                });
    }
}
