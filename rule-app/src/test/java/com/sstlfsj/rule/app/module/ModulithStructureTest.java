package com.sstlfsj.rule.app.module;

import com.sstlfsj.rule.RuleEngineApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 Modulith 能正确识别 config / eval / audit / web 等模块。
 * 骨架阶段不调用 verify()：rule-kernel 是跨模块共享库，
 * Modulith 在多 JAR 结构下对共享库包的 exposed 判断不适用（见 D33）。
 * 架构边界约束由 KernelArchTest（ArchUnit）保证。
 */
class ModulithStructureTest {

    private static final ApplicationModules MODULES =
            ApplicationModules.of(RuleEngineApplication.class);

    @Test
    void modulithRecognizesExpectedModules() {
        // 确认 Modulith 能发现核心业务模块
        assertTrue(MODULES.getModuleByName("config").isPresent(), "config 模块应被识别");
        assertTrue(MODULES.getModuleByName("eval").isPresent(), "eval 模块应被识别");
        assertTrue(MODULES.getModuleByName("audit").isPresent(), "audit 模块应被识别");
    }
}
