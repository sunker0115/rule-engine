package com.sstlfsj.rule.eval.internal.domain;

import java.util.Map;

/**
 * scene_action_binding 内存索引行（派发用）。defaultParams 在索引装载时由 JSON 串解析为 Map，
 * 派发时直传 ActionContext.params，热路径不再解析。
 *
 * @param actionType    actionType 路由键
 * @param defaultParams Scene 级默认参数（已解析），无则为空 Map
 */
public record SceneActionBindingRow(String actionType, Map<String, Object> defaultParams) {}
