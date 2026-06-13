package com.sstlfsj.rule.benchmark;

import com.sstlfsj.rule.eval.internal.validate.PayloadInputValidator;
import com.sstlfsj.rule.kernel.api.model.PayloadDependency;
import org.openjdk.jmh.annotations.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 量化运行期 payload 约束校验对吞吐的影响,三档对照:
 * none(必填+类型,无约束) / enumrange(一半 enum、一半 min/max) / pattern(每字段 RE2J 正则)。
 * 输入全合法(不抛),N=10 字段。pattern 档验证编译缓存生效——应与 none 档同量级、分配低。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class PayloadInputValidatorBenchmark {

    @Param({"none", "enumrange", "pattern"})
    public String mode;

    @Param({"10"})
    public int n;

    private List<PayloadDependency> deps;
    private Map<String, Object> payload;

    @Setup
    public void setup() {
        deps = new ArrayList<>();
        payload = new HashMap<>();
        for (int i = 0; i < n; i++) {
            String name = "f" + i;
            switch (mode) {
                case "none" -> {
                    deps.add(PayloadDependency.builder().name(name).dataType("STRING").required(true).build());
                    payload.put(name, "v" + i);
                }
                case "enumrange" -> {
                    if (i % 2 == 0) {
                        // enum 约束(STRING),给枚举内合法值
                        deps.add(PayloadDependency.builder().name(name).dataType("STRING").required(true)
                                .enumValues(List.of("a", "b", "c")).build());
                        payload.put(name, "b");
                    } else {
                        // 区间约束(DECIMAL),给区间内合法值
                        deps.add(PayloadDependency.builder().name(name).dataType("DECIMAL").required(true)
                                .minimum(0.0).maximum(100.0).build());
                        payload.put(name, 50.0);
                    }
                }
                case "pattern" -> {
                    deps.add(PayloadDependency.builder().name(name).dataType("STRING").required(true)
                            .pattern("\\w+").build());
                    payload.put(name, "abc" + i);
                }
                default -> throw new IllegalArgumentException("unknown mode " + mode);
            }
        }
    }

    @Benchmark
    public void validate() {
        PayloadInputValidator.validate(deps, payload);
    }
}
