package com.sstlfsj.rule.eval.internal.snapshot;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.internal.evaluator.ScriptExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ScriptWarmerTest {

    private final ScriptExecutor scriptExecutor = mock(ScriptExecutor.class);

    private ScriptWarmer warmer(PrecompileMode mode) {
        ScriptPrecompileProperties props = new ScriptPrecompileProperties();
        props.setMode(mode);
        return new ScriptWarmer(scriptExecutor, props);
    }

    @Test
    void eagerModeDelegatesToWarmUp() {
        List<RuleVersionSnapshot> snaps = List.of();
        warmer(PrecompileMode.EAGER).warmUpIfEager(snaps);
        verify(scriptExecutor).warmUp(snaps);
    }

    @Test
    void lazyModeIsNoOp() {
        warmer(PrecompileMode.LAZY).warmUpIfEager(List.of());
        verify(scriptExecutor, never()).warmUp(org.mockito.ArgumentMatchers.any());
    }
}
