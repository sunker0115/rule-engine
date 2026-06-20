package com.sstlfsj.rule.eval.internal.metric.stream;

import com.sstlfsj.rule.kernel.api.annotation.MetricSourceType;
import com.sstlfsj.rule.kernel.api.model.SourceType;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricSourceHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 验证 StreamFeatureMetricSourceHandler 被 Spring 组件扫描发现并正确标注 @MetricSourceType(STREAM)。 */
class StreamFeatureMetricSourceHandlerAssemblyTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Configuration
    @ComponentScan(basePackageClasses = StreamFeatureMetricSourceHandler.class)
    static class TestConfig {
        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }
    }

    @Test
    void handlerRegisteredAsSpringBean() {
        runner.run(ctx ->
                assertThat(ctx).hasSingleBean(StreamFeatureMetricSourceHandler.class));
    }

    @Test
    void handlerAnnotatedWithStreamSourceType() {
        runner.run(ctx -> {
            MetricSourceType ann = ctx.getBean(StreamFeatureMetricSourceHandler.class)
                    .getClass().getAnnotation(MetricSourceType.class);
            assertThat(ann).isNotNull();
            assertThat(ann.value()).isEqualTo(SourceType.STREAM);
        });
    }

    @Test
    void handlerImplementsMetricSourceHandler() {
        runner.run(ctx ->
                assertThat(ctx.getBean(StreamFeatureMetricSourceHandler.class))
                        .isInstanceOf(MetricSourceHandler.class));
    }
}
