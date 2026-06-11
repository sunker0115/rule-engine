package com.sstlfsj.rule.eval.internal.metric;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

/**
 * 为 Caffeine 动态生成的缓存实现类注册 native 反射元数据。
 *
 * <p>Caffeine 依缓存特性在运行期拼出 com.github.benmanes.caffeine.cache 包内的全大写类名
 * （本缓存 maximumSize + 变长 expireAfter 组合对应 {@code SSMSA}），经 MethodHandles 反射加载。
 * GraalVM 社区 reachability metadata 未覆盖该生成类，native 下须显式注册其构造器，否则启动即
 * ClassNotFoundException。该类为 caffeine 包级私有，故按字符串名（{@link TypeReference}）注册，
 * 不能用 {@code @RegisterReflectionForBinding}（编译期引用不到）。
 */
class CaffeineNativeHints implements RuntimeHintsRegistrar {

    private static final String SSMSA = "com.github.benmanes.caffeine.cache.SSMSA";

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // Caffeine 既反射 new SSMSA(...)（构造器），又反射读其静态 FACTORY 字段，故构造器/方法/字段全注册
        hints.reflection().registerType(TypeReference.of(SSMSA),
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_DECLARED_METHODS,
                MemberCategory.ACCESS_DECLARED_FIELDS);
    }
}
