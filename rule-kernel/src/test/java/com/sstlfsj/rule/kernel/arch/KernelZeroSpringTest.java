package com.sstlfsj.rule.kernel.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "com.sstlfsj.rule.kernel",
        importOptions = ImportOption.DoNotIncludeTests.class
)
public class KernelZeroSpringTest {

    @ArchTest
    static final ArchRule noSpringDependencies =
            noClasses()
                    .that().resideInAPackage("com.sstlfsj.rule.kernel..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("org.springframework..")
                    .because("rule-kernel 是零 Spring 纯 Java 库，不允许任何 Spring 依赖");
}
