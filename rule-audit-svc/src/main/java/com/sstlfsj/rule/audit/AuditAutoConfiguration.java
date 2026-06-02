package com.sstlfsj.rule.audit;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/** 自动装配规则审计模块。 */
@AutoConfiguration
@ComponentScan("com.sstlfsj.rule.audit.internal")
public class AuditAutoConfiguration {
}
