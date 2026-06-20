package com.sstlfsj.rule.eval.internal.outcomesource;

import com.sstlfsj.rule.eval.api.service.OutcomePullResult;
import com.sstlfsj.rule.eval.api.service.OutcomeSource;
import com.sstlfsj.rule.eval.api.service.OutcomeSourceConfig;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 按 OutcomeSourceConfig 子类型收集 OutcomeSource 并路由。Spring 注入 List&lt;OutcomeSource&gt; 自动收集。 */
@Component
public class OutcomeSourceRegistry {

    private final Map<Class<? extends OutcomeSourceConfig>, OutcomeSource<?>> byConfigType = new HashMap<>();

    public OutcomeSourceRegistry(List<OutcomeSource<?>> sources) {
        for (OutcomeSource<?> s : sources) {
            if (byConfigType.putIfAbsent(s.configType(), s) != null) {
                throw new IllegalStateException("多个 OutcomeSource 声明同一 configType=" + s.configType());
            }
        }
    }

    /**
     * 路由拉取：按 source 运行时类型查实现，转型后委派。
     *
     * @param source    源配置
     * @param watermark 上次水位（null=首次全量）
     * @param tenantId  租户 id
     * @return 标签行 + 新水位
     */
    @SuppressWarnings("unchecked")
    public <C extends OutcomeSourceConfig> OutcomePullResult pull(C source, Instant watermark, Long tenantId) {
        OutcomeSource<C> impl = (OutcomeSource<C>) byConfigType.get(source.getClass());
        if (impl == null) {
            throw new IllegalStateException("无 OutcomeSource 处理 configType=" + source.getClass().getSimpleName());
        }
        return impl.pull(source, watermark, tenantId);
    }
}
