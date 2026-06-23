package com.sstlfsj.rule.web.common;

import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import tools.jackson.databind.json.JsonMapper;

/** 测试工具：为 standalone MockMvc 提供注册了 ProblemDetailJacksonMixin 的 message converter。 */
public final class TestMessageConverters {

    private TestMessageConverters() {}

    /** @return 带 ProblemDetail mixin 的 JacksonJsonHttpMessageConverter，确保 properties 展平到顶层 JSON 字段。 */
    public static JacksonJsonHttpMessageConverter problemDetailConverter() {
        JsonMapper mapper = JsonMapper.builder()
                .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
                .build();
        return new JacksonJsonHttpMessageConverter(mapper);
    }
}
