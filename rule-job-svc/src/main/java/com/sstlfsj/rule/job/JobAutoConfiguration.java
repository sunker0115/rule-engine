package com.sstlfsj.rule.job;

import com.sstlfsj.rule.job.internal.scheduler.ThreadPoolSchedulerAdapter;
import com.sstlfsj.rule.kernel.api.spi.scheduler.Scheduler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/** 自动装配 Job 模块（D11 Trigger 适配器）。 */
@AutoConfiguration
@ComponentScan("com.sstlfsj.rule.job.internal")
public class JobAutoConfiguration {

    /**
     * 进程内调度器（{@code ThreadPoolTaskScheduler} + {@code CronTrigger}，单实例）。
     *
     * <p>由 {@code engine.rule.job.scheduler} 选择调度器实现，默认 {@code in-process}：
     * <ul>
     *   <li>{@code in-process}（默认 / 未配置）：装配本进程内调度器；</li>
     *   <li>{@code xxl-job}：不装配进程内实现，改由 xxl-job 适配的 {@code Scheduler} Bean 接管
     *       （需引入对应实现，业务侧 JobDefinition / JobExecution 不变）。</li>
     * </ul>
     * 同时保留 {@link ConditionalOnMissingBean}：外部显式注册的 Scheduler Bean 始终优先。
     *
     * @return ThreadPoolSchedulerAdapter 实例
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(Scheduler.class)
    @ConditionalOnProperty(prefix = "engine.rule.job", name = "scheduler",
            havingValue = "in-process", matchIfMissing = true)
    public Scheduler scheduler() {
        return new ThreadPoolSchedulerAdapter();
    }
}
