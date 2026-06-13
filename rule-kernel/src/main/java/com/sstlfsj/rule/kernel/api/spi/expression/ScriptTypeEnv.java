package com.sstlfsj.rule.kernel.api.spi.expression;

import com.sstlfsj.rule.kernel.api.model.DataType;

import java.util.Map;

/**
 * 发布期类型检查的变量类型环境:被引用变量的声明类型,按命名空间分组。
 *
 * <p>{@code subject.*} 命名空间运行期动态、无固定类型,不在此声明——由引擎按开放类型(dyn)处理。
 *
 * @param metrics {@code metrics.<code>} → 声明类型(来自 metric_definition.dataType)
 * @param payload {@code payload.<field>} → 声明类型(来自 scene.payloadSchema)
 */
public record ScriptTypeEnv(Map<String, DataType> metrics, Map<String, DataType> payload) {

    public ScriptTypeEnv {
        metrics = Map.copyOf(metrics);
        payload = Map.copyOf(payload);
    }
}
