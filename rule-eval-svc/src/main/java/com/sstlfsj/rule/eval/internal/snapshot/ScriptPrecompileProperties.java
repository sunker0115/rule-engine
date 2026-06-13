package com.sstlfsj.rule.eval.internal.snapshot;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 脚本规则预编译加热配置(前缀 {@code engine.rule.script.precompile})。
 *
 * <p>统一开关控制脚本规则编译产物的加热时机:LAZY 首次评估编译、EAGER 快照加载期预编译。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "engine.rule.script.precompile")
public class ScriptPrecompileProperties {

    /** 加热模式;LAZY(默认)= 首次评估编译,EAGER = 快照加载期预编译。 */
    private PrecompileMode mode = PrecompileMode.LAZY;
}
