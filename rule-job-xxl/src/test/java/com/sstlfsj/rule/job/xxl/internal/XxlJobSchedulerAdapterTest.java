package com.sstlfsj.rule.job.xxl.internal;

import com.sstlfsj.rule.kernel.api.spi.scheduler.TaskRunCallback;
import com.xxl.job.core.context.XxlJobContext;
import com.xxl.job.core.executor.XxlJobExecutor;
import com.xxl.job.core.handler.IJobHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证通用 handler 模式:唯一 handler {@code scheduled-task-runner} 按 param=taskId 派发,
 * schedule 缓存 runnable + seed admin(UNIVERSAL_HANDLER + executorParam=taskId),
 * 缺本地缓存时经 {@link ObjectProvider} 惰性取 {@link TaskRunCallback} 降级。
 */
class XxlJobSchedulerAdapterTest {

    /** 用 XxlJobContext ThreadLocal 模拟 admin 派发的 jobParam,触发通用 handler。 */
    private static void triggerUniversalHandler(String jobParam) throws Exception {
        XxlJobContext.setXxlJobContext(new XxlJobContext(0L, jobParam, 0L, 0L, "", 0, 1));
        try {
            IJobHandler handler = XxlJobExecutor.loadJobHandler(XxlJobSchedulerAdapter.UNIVERSAL_HANDLER);
            assertThat(handler).isNotNull();
            handler.execute();
        } finally {
            XxlJobContext.setXxlJobContext(null);
        }
    }

    /** 创建 mock ObjectProvider,getObject() 返回给定回调。 */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<TaskRunCallback> mockProvider(TaskRunCallback callback) {
        ObjectProvider<TaskRunCallback> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(callback);
        return provider;
    }

    @Test
    void constructorRegistersUniversalHandler() {
        new XxlJobSchedulerAdapter(mock(XxlJobAdminClient.class), mockProvider(id -> {}));
        assertThat(XxlJobExecutor.loadJobHandler(XxlJobSchedulerAdapter.UNIVERSAL_HANDLER)).isNotNull();
    }

    @Test
    void scheduleCachesRunnableAndSeedsAdminWithUniversalHandler() {
        XxlJobAdminClient admin = mock(XxlJobAdminClient.class);
        when(admin.ensureJobSeeded(
                eq("task-1"),
                eq(XxlJobSchedulerAdapter.UNIVERSAL_HANDLER),
                eq("0 0 * * * ?"),
                eq("FIRST"),
                eq("1"))).thenReturn(42L);
        XxlJobSchedulerAdapter adapter = new XxlJobSchedulerAdapter(admin, mockProvider(id -> {}));

        adapter.schedule("scheduled-task:1", "0 0 * * * ?", () -> {});

        // seed admin:jobDesc=task-1、handler=UNIVERSAL_HANDLER、executorParam=taskId
        verify(admin).ensureJobSeeded(
                "task-1", XxlJobSchedulerAdapter.UNIVERSAL_HANDLER, "0 0 * * * ?", "FIRST", "1");
    }

    @Test
    void universalHandlerRunsCachedRunnableByTaskId() throws Exception {
        XxlJobAdminClient admin = mock(XxlJobAdminClient.class);
        AtomicLong fallbackCalled = new AtomicLong(-1);
        XxlJobSchedulerAdapter adapter = new XxlJobSchedulerAdapter(admin, mockProvider(fallbackCalled::set));

        AtomicInteger ran = new AtomicInteger();
        adapter.schedule("scheduled-task:7", "0 0 * * * ?", ran::incrementAndGet);

        triggerUniversalHandler("7");

        // 命中本地缓存:跑 runnable,不走 fallback
        assertThat(ran.get()).isEqualTo(1);
        assertThat(fallbackCalled.get()).isEqualTo(-1);
    }

    @Test
    void universalHandlerFallsBackToCallbackWhenNotCached() throws Exception {
        XxlJobAdminClient admin = mock(XxlJobAdminClient.class);
        AtomicLong fallbackCalled = new AtomicLong(-1);
        new XxlJobSchedulerAdapter(admin, mockProvider(fallbackCalled::set));

        // 未 schedule 过 taskId=99:handler 降级调 callback
        triggerUniversalHandler("99");

        assertThat(fallbackCalled.get()).isEqualTo(99L);
    }

    @Test
    void unscheduleRemovesRunnableSoHandlerFallsBack() throws Exception {
        XxlJobAdminClient admin = mock(XxlJobAdminClient.class);
        AtomicLong fallbackCalled = new AtomicLong(-1);
        XxlJobSchedulerAdapter adapter = new XxlJobSchedulerAdapter(admin, mockProvider(fallbackCalled::set));

        AtomicInteger ran = new AtomicInteger();
        adapter.schedule("scheduled-task:5", "0 0 * * * ?", ran::incrementAndGet);
        adapter.unschedule("scheduled-task:5");

        // 注销后本地缓存清除:handler 不再跑 runnable,转走 fallback
        triggerUniversalHandler("5");
        assertThat(ran.get()).isZero();
        assertThat(fallbackCalled.get()).isEqualTo(5L);
    }

    @Test
    void scheduleBroadcastRegistersConsumerAndTriggerSeeds() {
        XxlJobAdminClient admin = mock(XxlJobAdminClient.class);
        when(admin.ensureJobSeeded(eq("config-broadcast"), eq(XxlJobSchedulerAdapter.BROADCAST_HANDLER),
                anyString(), eq("SHARDING_BROADCAST"), eq(""))).thenReturn(55L);
        XxlJobSchedulerAdapter adapter = new XxlJobSchedulerAdapter(admin, mockProvider(id -> {}));

        java.util.List<String> received = new java.util.ArrayList<>();
        adapter.scheduleBroadcast("config-change", received::add);
        adapter.triggerBroadcast("config-change", "scene:9100:fraud_check:true");

        verify(admin).ensureJobSeeded(
                "config-broadcast", XxlJobSchedulerAdapter.BROADCAST_HANDLER,
                "0 0 0 1 1 ?", "SHARDING_BROADCAST", "");
        verify(admin).triggerJob(55L, "scene:9100:fraud_check:true");
    }

    @Test
    void broadcastHandlerDispatchesToConsumerByParam() throws Exception {
        XxlJobAdminClient admin = mock(XxlJobAdminClient.class);
        XxlJobSchedulerAdapter adapter = new XxlJobSchedulerAdapter(admin, mockProvider(id -> {}));

        java.util.List<String> received = new java.util.ArrayList<>();
        adapter.scheduleBroadcast("config-change", received::add);

        // 模拟 XXL 派发广播 handler（param="scene:9100:fraud_check:true" 非 taskId）
        XxlJobContext.setXxlJobContext(new XxlJobContext(0L, "scene:9100:fraud_check:true", 0L, 0L, "", 0, 1));
        try {
            IJobHandler handler = XxlJobExecutor.loadJobHandler(XxlJobSchedulerAdapter.BROADCAST_HANDLER);
            assertThat(handler).isNotNull();
            handler.execute();
        } finally {
            XxlJobContext.setXxlJobContext(null);
        }

        assertThat(received).containsExactly("scene:9100:fraud_check:true");
    }

    @Test
    void unsupportedBroadcastCode_failsFast() {
        XxlJobAdminClient admin = mock(XxlJobAdminClient.class);
        XxlJobSchedulerAdapter adapter = new XxlJobSchedulerAdapter(admin, mockProvider(id -> {}));

        // 非 config-change 的 code 立即报错，而非静默丢失（XXL 单广播 code 约束）
        assertThatThrownBy(() -> adapter.scheduleBroadcast("cache-evict", p -> {}))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("config-change");
        assertThatThrownBy(() -> adapter.triggerBroadcast("cache-evict", "x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
