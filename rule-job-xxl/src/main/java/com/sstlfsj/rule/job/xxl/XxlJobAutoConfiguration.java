package com.sstlfsj.rule.job.xxl;

import com.sstlfsj.rule.job.xxl.internal.HttpXxlJobAdminClient;
import com.sstlfsj.rule.job.xxl.internal.XxlJobAdminClient;
import com.sstlfsj.rule.job.xxl.internal.XxlJobSchedulerAdapter;
import com.sstlfsj.rule.kernel.api.spi.scheduler.Scheduler;
import com.sstlfsj.rule.kernel.api.spi.scheduler.TaskRunCallback;
import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

/**
 * xxl-job 调度适配装配：仅当 {@code engine.rule.job.scheduler=xxl-job} 时生效。
 *
 * <p>提供 {@link Scheduler} 接管进程内实现（{@link ConditionalOnMissingBean} 保证外部自定义 Scheduler 优先），
 * 内制侧 scheduled_task / scheduled_task_execution / TriggerExecutor 不变。
 */
@AutoConfiguration
@EnableConfigurationProperties(XxlJobProperties.class)
@ConditionalOnProperty(prefix = "engine.rule.job", name = "scheduler", havingValue = "xxl-job")
public class XxlJobAutoConfiguration {

    /**
     * xxl-job 执行器：注册到 admin + 起 Netty EmbedServer 接收调度回调。
     * 由 {@code SmartInitializingSingleton} 自动 start；{@code enabled=false} 时 start 跳过。
     *
     * @param props xxl 配置
     * @return XxlJobSpringExecutor 实例
     */
    @Bean(destroyMethod = "destroy")
    public XxlJobSpringExecutor xxlJobExecutor(XxlJobProperties props) {
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(props.getAdminAddresses());
        executor.setAccessToken(props.getAccessToken());
        executor.setAppname(props.getAppname());
        executor.setAddress(props.getAddress());
        executor.setIp(props.getIp());
        executor.setPort(props.getPort());
        executor.setLogPath(props.getLogPath());
        executor.setLogRetentionDays(props.getLogRetentionDays());
        executor.setEnabled(props.isEnabled());
        return executor;
    }

    /**
     * admin 接入客户端（JDK HttpClient + 注入的全局 ObjectMapper）。
     *
     * @param props        xxl 配置（admin 地址 / 账号）
     * @param objectMapper 全局 JSON 序列化 Bean
     * @return XxlJobAdminClient 实例
     */
    @Bean
    public XxlJobAdminClient xxlJobAdminClient(XxlJobProperties props, ObjectMapper objectMapper) {
        return new HttpXxlJobAdminClient(props, objectMapper);
    }

    /**
     * Scheduler 的 xxl 实现；外部显式注册的 Scheduler Bean 始终优先。
     *
     * @param adminClient      admin 接入客户端
     * @param callbackProvider TaskRunCallback 惰性 provider（惰性解析断构造期 bean 循环依赖）
     * @return XxlJobSchedulerAdapter 实例
     */
    @Bean
    @ConditionalOnMissingBean(Scheduler.class)
    public Scheduler scheduler(XxlJobAdminClient adminClient, ObjectProvider<TaskRunCallback> callbackProvider) {
        return new XxlJobSchedulerAdapter(adminClient, callbackProvider);
    }
}
