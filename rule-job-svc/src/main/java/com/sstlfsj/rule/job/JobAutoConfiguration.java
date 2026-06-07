package com.sstlfsj.rule.job;

import com.sstlfsj.rule.job.internal.scheduler.ThreadPoolSchedulerAdapter;
import com.sstlfsj.rule.kernel.api.spi.scheduler.Scheduler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/** 自动装配 Job 模块（D11 Trigger 适配器）。 */
@AutoConfiguration
@ComponentScan("com.sstlfsj.rule.job.internal")
public class JobAutoConfiguration {

    /**
     * 进程内调度器（单实例）。外部注册自定义 Scheduler Bean（如 xxl-job 适配）可覆盖此默认值。
     *
     * @return ThreadPoolSchedulerAdapter 实例
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(Scheduler.class)
    public Scheduler scheduler() {
        return new ThreadPoolSchedulerAdapter();
    }
}
