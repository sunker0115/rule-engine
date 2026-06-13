package com.sstlfsj.rule.eval.internal.snapshot;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.internal.evaluator.ScriptExecutor;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * 按预编译配置决定是否在快照加载期预热脚本规则的编译产物。
 *
 * <p>EAGER 模式下委托 {@link ScriptExecutor#warmUp} 预编译,首次评估即命中引擎缓存;
 * LAZY 模式 no-op,保持首次评估时编译的默认行为。
 */
@Component
public class ScriptWarmer {

    private final ScriptExecutor scriptExecutor;
    private final ScriptPrecompileProperties properties;

    public ScriptWarmer(ScriptExecutor scriptExecutor, ScriptPrecompileProperties properties) {
        this.scriptExecutor = scriptExecutor;
        this.properties = properties;
    }

    /**
     * EAGER 模式下预编译给定快照中的脚本规则;LAZY 模式不做任何事。
     *
     * @param snapshots 本次加载的规则版本快照(非脚本规则由 warmUp 自行跳过)
     */
    public void warmUpIfEager(Collection<RuleVersionSnapshot> snapshots) {
        if (properties.getMode() == PrecompileMode.EAGER) {
            scriptExecutor.warmUp(snapshots);
        }
    }
}
