package com.sstlfsj.rule.benchmark;

import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;
import java.util.concurrent.TimeUnit;

/** 内嵌 JMH 跑（JUnit管理classpath，@Fork(0) 免 forks）。 */
class JmhRunner {
    @Test
    void runJmh() throws Exception {
        new Runner(new OptionsBuilder()
                .include(EvalEngineBenchmark.class.getSimpleName())
                .warmupIterations(2).warmupTime(TimeValue.seconds(1))
                .measurementIterations(3).measurementTime(TimeValue.seconds(1))
                .forks(0) // in-process，免 fork classpath 问题
                .build()).run();
    }
}
