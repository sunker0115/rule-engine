package com.sstlfsj.rule.job.xxl.internal;

import com.xxl.job.core.executor.XxlJobExecutor;
import com.xxl.job.core.handler.IJobHandler;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证 adapter 把 task 闭包注册成 IJobHandler（名=jobCode）、seed admin、注销覆盖为 no-op。 */
class XxlJobSchedulerAdapterTest {

    @Test
    void scheduleRegistersHandlerSeedsAdminAndRunsTask() throws Exception {
        XxlJobAdminClient admin = mock(XxlJobAdminClient.class);
        when(admin.ensureJobSeeded(eq("job:1"), eq("job:1"), eq("0 0 * * * ?"), eq(""))).thenReturn(42L);
        XxlJobSchedulerAdapter adapter = new XxlJobSchedulerAdapter(admin);

        AtomicInteger ran = new AtomicInteger();
        adapter.schedule("job:1", "0 0 * * * ?", ran::incrementAndGet);

        // seed 被调用
        verify(admin).ensureJobSeeded("job:1", "job:1", "0 0 * * * ?", "");
        // handler 注册到全局 registry，名=jobCode
        IJobHandler handler = XxlJobExecutor.loadJobHandler("job:1");
        assertThat(handler).isNotNull();
        // 触发 handler 即运行 task
        handler.execute();
        assertThat(ran.get()).isEqualTo(1);
    }

    @Test
    void unscheduleReplacesHandlerWithNoop() throws Exception {
        XxlJobAdminClient admin = mock(XxlJobAdminClient.class);
        when(admin.ensureJobSeeded(eq("job:2"), eq("job:2"), eq("0 0 * * * ?"), eq(""))).thenReturn(7L);
        XxlJobSchedulerAdapter adapter = new XxlJobSchedulerAdapter(admin);

        AtomicInteger ran = new AtomicInteger();
        adapter.schedule("job:2", "0 0 * * * ?", ran::incrementAndGet);
        adapter.unschedule("job:2");

        // 注销后 handler 仍存在但为 no-op：execute 不再跑 task
        IJobHandler handler = XxlJobExecutor.loadJobHandler("job:2");
        assertThat(handler).isNotNull();
        handler.execute();
        assertThat(ran.get()).isZero();
    }
}
