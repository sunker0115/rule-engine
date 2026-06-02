package com.sstlfsj.rule.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/** Auto-configures the rule configuration module. */
@AutoConfiguration
@ComponentScan("com.sstlfsj.rule.config.internal")
public class ConfigAutoConfiguration {
}
