package com.sstlfsj.rule.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot 启动入口，扫描整个 com.sstlfsj.rule 包树。 */
@SpringBootApplication(scanBasePackages = "com.sstlfsj.rule")
public class RuleEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(RuleEngineApplication.class, args);
    }
}
