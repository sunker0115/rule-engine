package com.sstlfsj.rule.eval.internal.metric;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证为 Caffeine 生成类 SSMSA 注册了构造器反射元数据。 */
class CaffeineNativeHintsTest {

    private static final String SSMSA = "com.github.benmanes.caffeine.cache.SSMSA";

    @Test
    void registersSsmsaConstructorsAndFields() {
        RuntimeHints hints = new RuntimeHints();
        new CaffeineNativeHints().registerHints(hints, getClass().getClassLoader());

        // 构造器（反射 new）与字段（反射读静态 FACTORY）均须注册
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(TypeReference.of(SSMSA))
                .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(TypeReference.of(SSMSA))
                .withMemberCategory(MemberCategory.ACCESS_DECLARED_FIELDS))
                .accepts(hints);
    }
}
