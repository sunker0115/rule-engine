package com.sstlfsj.rule.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/** 规则配置模块自动装配入口。 */
@AutoConfiguration
@ComponentScan("com.sstlfsj.rule.config.internal")
public class ConfigAutoConfiguration {
}
