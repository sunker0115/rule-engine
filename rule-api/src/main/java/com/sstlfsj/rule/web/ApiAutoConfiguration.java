package com.sstlfsj.rule.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/** rule-api 模块自动装配入口，扫描所有 Controller、Filter、Advice。 */
@AutoConfiguration
@ComponentScan("com.sstlfsj.rule.web")
public class ApiAutoConfiguration {
}
