package com.sstlfsj.rule.eval.internal;

import com.sstlfsj.rule.kernel.internal.evaluator.CompileErrorPolicy;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 编译执行器灰度配置 engine.rule.eval.compiled-executor.*。
 * enabled=false(默认)时行为与解释器逐字节一致。
 */
@Getter
@Setter
@ConfigurationProperties("engine.rule.eval.compiled-executor")
public class CompiledExecutorProperties {

    /** 是否启用编译执行器；false=全部走解释器。 */
    private boolean enabled = false;

    /** 编译白名单(规则 code)；enabled 且为空=全量编译，非空=仅列出的 code 走编译。 */
    private List<String> ruleCodeWhitelist = List.of();

    /** 编译失败处置策略，默认 FALLBACK。 */
    private CompileErrorPolicy onCompileError = CompileErrorPolicy.FALLBACK;
}
