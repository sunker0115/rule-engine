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

    @ArchTest
    static final ArchRule jobSvcMustNotDependOnEvalInternal =
            noClasses()
                    .that().resideInAPackage("com.sstlfsj.rule.job..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.sstlfsj.rule.eval.internal..")
                    .because("job 只能用 eval 的 api（EvalService），不碰 internal（D11）");

    @ArchTest
    static final ArchRule jobSvcMustNotDependOnConfigInternal =
            noClasses()
                    .that().resideInAPackage("com.sstlfsj.rule.job..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.sstlfsj.rule.config.internal..")
                    .because("job 只能用 config 的 api（SceneService），不碰 internal（D11）");

    @ArchTest
    static final ArchRule evalAndConfigMustNotDependOnJob =
            noClasses()
                    .that().resideInAPackage("com.sstlfsj.rule.eval..")
                    .or().resideInAPackage("com.sstlfsj.rule.config..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.sstlfsj.rule.job..")
                    .because("job 是下游 Trigger 适配器，eval/config 不得反向依赖（D11）");
}
