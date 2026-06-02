package com.sstlfsj.rule.app.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "com.sstlfsj.rule",
        importOptions = ImportOption.DoNotIncludeTests.class
)
public class KernelArchTest {

    @ArchTest
    static final ArchRule 内核禁止依赖Spring =
            noClasses()
                    .that().resideInAPackage("com.sstlfsj.rule.kernel..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("org.springframework..")
                    .because("rule-kernel 是零 Spring 纯 Java 库（见 09-skeleton.md §五）");

    @ArchTest
    static final ArchRule configSvc禁止依赖evalSvc =
            noClasses()
                    .that().resideInAPackage("com.sstlfsj.rule.config..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.sstlfsj.rule.eval..")
                    .because("svc 模块间禁止直接依赖（只通过 Modulith 事件通信，09-skeleton §五）");
}
