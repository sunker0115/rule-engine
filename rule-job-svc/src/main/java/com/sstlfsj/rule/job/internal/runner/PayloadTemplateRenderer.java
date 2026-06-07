package com.sstlfsj.rule.job.internal.runner;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * payloadTemplate 渲染器：模板值为 {@code "${col}"} 占位符的替换为主体行同名字段，其余原样保留。
 */
@Component
@RequiredArgsConstructor
class PayloadTemplateRenderer {

    private final ObjectMapper objectMapper;

    /**
     * 渲染 payload。
     *
     * @param templateJson payloadTemplate JSON 对象字符串，null / 空白返回空 map
     * @param subjectRow   主体行字段，供 {@code ${col}} 占位符取值
     * @return 渲染后的 payload map
     */
    Map<String, Object> render(String templateJson, Map<String, Object> subjectRow) {
        if (templateJson == null || templateJson.isBlank()) {
            return Map.of();
        }
        Map<String, Object> template = objectMapper.readValue(
                templateJson, new TypeReference<Map<String, Object>>() {});
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : template.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String s && s.startsWith("${") && s.endsWith("}")) {
                String column = s.substring(2, s.length() - 1);
                result.put(entry.getKey(), subjectRow.get(column));
            } else {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }
}
