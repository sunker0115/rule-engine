package com.sstlfsj.rule;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Spring Boot 启动入口，位于 com.sstlfsj.rule 根包以便 Modulith 识别子模块。 */
@SpringBootApplication
@EnableScheduling
public class RuleEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(RuleEngineApplication.class, args);
    }
}
