package com.sstlfsj.rule.eval;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/** Auto-configures the rule evaluation module. */
@AutoConfiguration
@ComponentScan("com.sstlfsj.rule.eval.internal")
public class EvalAutoConfiguration {
}
