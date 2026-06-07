package com.sstlfsj.rule.job.internal.runner;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayloadTemplateRendererTest {

    private final PayloadTemplateRenderer renderer = new PayloadTemplateRenderer(JsonMapper.builder().build());

    @Test
    void placeholderResolvedFromSubjectRow() {
        Map<String, Object> row = Map.of("userId", "u1", "amount", 100);
        Map<String, Object> result = renderer.render(
                "{\"uid\":\"${userId}\",\"amt\":\"${amount}\"}", row);
        assertEquals("u1", result.get("uid"));
        assertEquals(100, result.get("amt"));
    }

    @Test
    void literalValueKeptAsIs() {
        Map<String, Object> result = renderer.render(
                "{\"fixed\":\"literal\",\"num\":7}", Map.of());
        assertEquals("literal", result.get("fixed"));
        assertEquals(7, result.get("num"));
    }

    @Test
    void blankTemplateReturnsEmptyMap() {
        assertTrue(renderer.render(null, Map.of()).isEmpty());
        assertTrue(renderer.render("", Map.of()).isEmpty());
    }

    @Test
    void missingColumnResolvesToNull() {
        Map<String, Object> result = renderer.render("{\"uid\":\"${missing}\"}", Map.of("userId", "u1"));
        assertTrue(result.containsKey("uid"));
        assertEquals(null, result.get("uid"));
    }
}
