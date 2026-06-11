package com.baomidou.mybatisplus.aot;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 native 反射注册器为 MyBatis-Plus JSON TypeHandler 注册了构造器反射元数据。 */
class MyBatisPlusNativeImageConfigurationTest {

    private static final String JACKSON3 = "com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler";
    private static final String JACKSON = "com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler";

    @Test
    void registersJsonTypeHandlerConstructorsForReflection() {
        RuntimeHints hints = new RuntimeHints();
        AotUtils aotUtils = new AotUtils(hints, getClass().getClassLoader());

        new MyBatisPlusNativeImageConfiguration.MyBatisRuntimeHintsRegistrar()
                .registerJsonTypeHandlers(aotUtils);

        // Jackson3TypeHandler（项目实际使用）的构造器必须可反射，否则 @TableField autoResultMap 实例化失败
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(TypeReference.of(JACKSON3))
                .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS))
                .accepts(hints);
        // 旧版 JacksonTypeHandler 同在 classpath，一并注册（按需）
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(TypeReference.of(JACKSON))
                .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS))
                .accepts(hints);
    }
}
