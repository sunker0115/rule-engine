package com.sstlfsj.rule.config;

import com.sstlfsj.rule.config.internal.MetricProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

/** 规则配置模块自动装配入口。 */
@AutoConfiguration
@ComponentScan("com.sstlfsj.rule.config.internal")
@EnableConfigurationProperties(MetricProperties.class)
public class ConfigAutoConfiguration {
}
