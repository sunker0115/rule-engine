package com.sstlfsj.rule.kernel.api.model;

import java.time.Instant;
import java.util.Map;

/**
 * 一次求值的环境(ambient 配置),区别于"数据"(event/metrics)。
 * 当前含求值时刻 now + 场景默认参数 sceneDefaultParams(键见 {@link SceneDefaultParams});
 * 未来 locale / 引擎全局默认等 ambient 配置统一扩此,避免散参穿透多层签名。
 *
 * @param now               本次求值统一时刻(整棵 AST 共用)
 * @param sceneDefaultParams scene.default_params 快照(不可变);null 视为空
 */
public record EvalEnv(Instant now, Map<String, Object> sceneDefaultParams) {
    public EvalEnv {
        sceneDefaultParams = sceneDefaultParams == null ? Map.of() : Map.copyOf(sceneDefaultParams);
    }
}
