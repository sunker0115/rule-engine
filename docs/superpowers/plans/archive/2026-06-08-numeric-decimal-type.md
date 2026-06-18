# DECIMAL 数值类型 + 类型分派比较 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: 用 superpowers:subagent-driven-development 或 executing-plans 逐 task 执行。步骤用 `- [ ]` 勾选。

**Goal:** 引入 LONG/DOUBLE/DECIMAL 数值三态,按 dataType 分派比较(整型/浮点走原始比较零 BigDecimal,仅 DECIMAL 走 BigDecimal),消除每次数值比较无条件创建 BigDecimal 的分配(摸排 7.4%);顺带把 metric_definition 三个 ENUM 列改 VARCHAR + app 校验。

**Architecture:** kernel 比较经单一工厂 `ComparisonStrategyFactory.forType(dataType)` 分派;新增 Long/Double 策略,"快路径命中类型才走原始比较、否则回退 Decimal(BigDecimal),绝不截断";NaN/∞ 显式不命中。dataType 发布期由 config-svc `AstDataTypeResolver` 冻结。DB `data_type/source_type/status` 由 ENUM 改 VARCHAR,允许值校验上移 app。

**Tech Stack:** Java 25、Spring Boot 4、MyBatis-Plus、Flyway、纯 kernel(无 Spring)。

**环境(每 task 跑测试):**
```
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
```

**设计依据:** `docs/superpowers/specs/2026-06-08-numeric-decimal-type-design.md`

## 文件清单
- kernel `internal/condition/strategy/`:`NumericComparisonStrategy`→改名 `DecimalComparisonStrategy`;新增 `LongComparisonStrategy`、`DoubleComparisonStrategy`;改 `ComparisonStrategyFactory`、`DefaultComparisonStrategy`。
- config-svc `internal/publish/AstDataTypeResolver.java`:算子允许集加 DECIMAL。
- config-svc `db/migration/V1_6__metric_columns_enum_to_varchar.sql`:新建。
- config-svc `internal/service/MetricWriteServiceImpl.java` + 新 `internal/domain/MetricEnums.java`(允许集常量):app 校验。
- 测试:各策略 test、factory test、resolver test、metric write 校验 test、精度回归(现有数值算子 test)。

---

## Task 1: 改名 NumericComparisonStrategy → DecimalComparisonStrategy + 工厂加 DECIMAL case

**Files:** rename `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/strategy/NumericComparisonStrategy.java` → `DecimalComparisonStrategy.java`;Modify `ComparisonStrategyFactory.java`、`DefaultComparisonStrategy.java`;rename test `NumericComparisonStrategyTest.java`(若存在)。

- [ ] **Step 1: git mv + 改类名**
```bash
cd /Users/sunke/dev/ai-project/rule-engine
git mv rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/strategy/NumericComparisonStrategy.java \
       rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/strategy/DecimalComparisonStrategy.java
F=$(find rule-kernel/src/test -name 'NumericComparisonStrategyTest.java'); [ -n "$F" ] && git mv "$F" "$(dirname $F)/DecimalComparisonStrategyTest.java"
grep -rl 'NumericComparisonStrategy' rule-kernel/src | while read f; do sed -i '' 's/NumericComparisonStrategy/DecimalComparisonStrategy/g' "$f"; done
```
- 改名后 `DecimalComparisonStrategy` 类体不变(`toBigDecimal` via `new BigDecimal(n.toString())` + compareTo);类注释更新为"精确小数比较策略:DECIMAL dataType + 各 fast-path 的回退"。

- [ ] **Step 2: 工厂加 DECIMAL case(行为暂不变)**
`ComparisonStrategyFactory`:字段 `NUMERIC` 已随 sed 改为 `DECIMAL`(`private static final DecimalComparisonStrategy DECIMAL = new DecimalComparisonStrategy();`);switch 改为:
```java
case "LONG", "DOUBLE", "DECIMAL" -> DECIMAL;   // T2/T3 再拆 LONG/DOUBLE
case "STRING"   -> STRING;
case "BOOLEAN"  -> BOOLEAN;
case "DATE"     -> DATE;
case "DATETIME" -> DATETIME;
default         -> DEFAULT;
```
(`DefaultComparisonStrategy` 内 `NUMERIC` 引用亦随 sed 变 `DECIMAL`,行为不变。)

- [ ] **Step 3: 跑 kernel 测试确认全绿(纯改名,行为不变)**
Run: `$MVN -pl rule-kernel test 2>&1 | grep -E 'Tests run:.*Failures|BUILD' | tail -2`
Expected: Failures:0,BUILD SUCCESS;`grep -rn NumericComparisonStrategy rule-kernel/src` 为空。

- [ ] **Step 4: 提交**
```bash
git add -A rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/strategy/ rule-kernel/src/test
git commit -m "refactor(kernel): NumericComparisonStrategy 改名 DecimalComparisonStrategy + 工厂 DECIMAL case"
```

---

## Task 2: LongComparisonStrategy + 工厂 LONG 路由

**Files:** Create `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/strategy/LongComparisonStrategy.java`、Test `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/strategy/LongComparisonStrategyTest.java`;Modify `ComparisonStrategyFactory.java`。

- [ ] **Step 1: 写失败测试**
```java
package com.sstlfsj.rule.kernel.internal.condition.strategy;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class LongComparisonStrategyTest {
    private final LongComparisonStrategy s = new LongComparisonStrategy();

    @Test void integralFastPath() {
        assertThat(s.compare(5L, 3L)).isPositive();
        assertThat(s.compare(3, 3L)).isZero();           // Integer actual
        assertThat(s.compare(2L, "3")).isNegative();     // String operand
        assertThat(s.equals(7L, 7L)).isTrue();
    }
    @Test void fractionalActual_fallsBackToDecimal_noTruncation() {
        // 声明 LONG 但 actual 是 3.7：不可截断成 3（否则 3.7>=3.5 会判错）
        assertThat(s.compare(3.7d, new BigDecimal("3.5"))).isPositive();
        assertThat(s.compare(3.7d, 4L)).isNegative();
    }
    @Test void nullOrUnparsable_sentinel() {
        assertThat(s.compare(null, 1L)).isEqualTo(Integer.MAX_VALUE);
        assertThat(s.compare(1L, "x")).isEqualTo(Integer.MAX_VALUE);
        assertThat(s.equals(null, 1L)).isFalse();
    }
}
```

- [ ] **Step 2: 跑确认失败**
Run: `$MVN -pl rule-kernel test -Dtest='LongComparisonStrategyTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败(类不存在)。

- [ ] **Step 3: 实现**
```java
package com.sstlfsj.rule.kernel.internal.condition.strategy;

/** LONG 比较:整型快路径 Long.compare(零分配);非整型/超范围回退 DecimalComparisonStrategy(绝不截断)。 */
class LongComparisonStrategy implements ComparisonStrategy {

    private static final DecimalComparisonStrategy FALLBACK = new DecimalComparisonStrategy();

    @Override
    public int compare(Object actual, Object operand) {
        Long a = toLong(actual);
        Long b = toLong(operand);
        if (a == null || b == null) return FALLBACK.compare(actual, operand);  // 非整型/解析失败/null → 精确回退或哨兵
        return Long.compare(a, b);
    }

    @Override
    public boolean equals(Object actual, Object operand) {
        Long a = toLong(actual);
        Long b = toLong(operand);
        if (a == null || b == null) return FALLBACK.equals(actual, operand);
        return a.longValue() == b.longValue();
    }

    /** 整型 Number(Long/Integer/Short/Byte)与整型 String → long;其余(Double/Float/BigDecimal/小数 String/null)→ null(触发回退,不截断)。 */
    private static Long toLong(Object o) {
        if (o instanceof Long l) return l;
        if (o instanceof Integer || o instanceof Short || o instanceof Byte) return ((Number) o).longValue();
        if (o instanceof String s) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }
}
```
工厂:`case "LONG" -> LONG;`(新增 `private static final LongComparisonStrategy LONG = new LongComparisonStrategy();`;从 `case "LONG","DOUBLE","DECIMAL"` 里摘掉 LONG → `case "DOUBLE","DECIMAL" -> DECIMAL;`)。

- [ ] **Step 4: 跑确认通过 + kernel 全量**
Run: `$MVN -pl rule-kernel test -Dtest='LongComparisonStrategyTest' -Dsurefire.failIfNoSpecifiedTests=false` → PASS;再 `$MVN -pl rule-kernel test 2>&1 | grep -E 'Tests run:.*Failures|BUILD' | tail -2` → 全绿(现有 LONG 算子测试走新路径,结果不变)。

- [ ] **Step 5: 提交**
```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/strategy/LongComparisonStrategy.java rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/strategy/ComparisonStrategyFactory.java rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/strategy/LongComparisonStrategyTest.java
git commit -m "perf(kernel): LongComparisonStrategy(整型 Long.compare 快路径 + Decimal 回退,不截断)"
```

---

## Task 3: DoubleComparisonStrategy + 工厂 DOUBLE 路由

**Files:** Create `DoubleComparisonStrategy.java` + Test;Modify `ComparisonStrategyFactory.java`。

- [ ] **Step 1: 写失败测试**
```java
package com.sstlfsj.rule.kernel.internal.condition.strategy;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class DoubleComparisonStrategyTest {
    private final DoubleComparisonStrategy s = new DoubleComparisonStrategy();

    @Test void doubleFastPath() {
        assertThat(s.compare(2.5d, 2.0d)).isPositive();
        assertThat(s.compare(1, 1.0d)).isZero();
        assertThat(s.compare(2.0d, "3.5")).isNegative();
        assertThat(s.equals(1.5d, 1.5d)).isTrue();
    }
    @Test void nanInfinity_sentinel_notHit() {
        assertThat(s.compare(Double.NaN, 1.0d)).isEqualTo(Integer.MAX_VALUE);
        assertThat(s.compare(Double.POSITIVE_INFINITY, 1.0d)).isEqualTo(Integer.MAX_VALUE);
        assertThat(s.equals(Double.NaN, Double.NaN)).isFalse();
    }
    @Test void bigDecimalOperand_fallsBackToDecimal() {
        assertThat(s.compare(new BigDecimal("2.5"), 2.0d)).isPositive();
    }
    @Test void nullOrUnparsable_sentinel() {
        assertThat(s.compare(null, 1.0d)).isEqualTo(Integer.MAX_VALUE);
        assertThat(s.compare(1.0d, "x")).isEqualTo(Integer.MAX_VALUE);
    }
}
```

- [ ] **Step 2: 跑确认失败**
Run: `$MVN -pl rule-kernel test -Dtest='DoubleComparisonStrategyTest' -Dsurefire.failIfNoSpecifiedTests=false` → 编译失败。

- [ ] **Step 3: 实现**
```java
package com.sstlfsj.rule.kernel.internal.condition.strategy;

import java.math.BigDecimal;

/** DOUBLE 比较:Double.compare(零分配);NaN/∞ 显式哨兵不命中;BigDecimal 操作数回退 Decimal。 */
class DoubleComparisonStrategy implements ComparisonStrategy {

    private static final DecimalComparisonStrategy FALLBACK = new DecimalComparisonStrategy();

    @Override
    public int compare(Object actual, Object operand) {
        if (actual instanceof BigDecimal || operand instanceof BigDecimal) return FALLBACK.compare(actual, operand);
        Double a = toDouble(actual);
        Double b = toDouble(operand);
        if (a == null || b == null) return Integer.MAX_VALUE;                 // 解析失败/null → 不命中
        if (a.isNaN() || a.isInfinite() || b.isNaN() || b.isInfinite()) return Integer.MAX_VALUE;  // 坏数据不命中,不用 Double.compare 的 NaN-最大全序
        return Double.compare(a, b);
    }

    @Override
    public boolean equals(Object actual, Object operand) {
        if (actual instanceof BigDecimal || operand instanceof BigDecimal) return FALLBACK.equals(actual, operand);
        Double a = toDouble(actual);
        Double b = toDouble(operand);
        if (a == null || b == null) return false;
        if (a.isNaN() || a.isInfinite() || b.isNaN() || b.isInfinite()) return false;
        return a.doubleValue() == b.doubleValue();
    }

    /** Number → doubleValue;String → parseDouble;否则 null。 */
    private static Double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) {
            try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }
}
```
工厂:`case "DOUBLE" -> DOUBLE;`(新增 `private static final DoubleComparisonStrategy DOUBLE = new DoubleComparisonStrategy();`;DECIMAL 单独 `case "DECIMAL" -> DECIMAL;`)。最终 switch:
```java
case "LONG"     -> LONG;
case "DOUBLE"   -> DOUBLE;
case "DECIMAL"  -> DECIMAL;
case "STRING"   -> STRING;
case "BOOLEAN"  -> BOOLEAN;
case "DATE"     -> DATE;
case "DATETIME" -> DATETIME;
default         -> DEFAULT;
```

- [ ] **Step 4: 跑确认通过 + kernel 全量**
Run: `$MVN -pl rule-kernel test -Dtest='DoubleComparisonStrategyTest' ...` → PASS;`$MVN -pl rule-kernel test 2>&1 | grep -E 'Tests run:.*Failures|BUILD' | tail -2` → 全绿(现有 DOUBLE 算子测试对同组 double 结果不变)。

- [ ] **Step 5: 提交**
```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/strategy/DoubleComparisonStrategy.java rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/strategy/ComparisonStrategyFactory.java rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/strategy/DoubleComparisonStrategyTest.java
git commit -m "perf(kernel): DoubleComparisonStrategy(Double.compare;NaN/∞ 不命中;BigDecimal 回退)"
```

---

## Task 4: AstDataTypeResolver 允许 DECIMAL(config-svc 发布期)

**Files:** Modify `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/AstDataTypeResolver.java`;Test 其测试类。

- [ ] **Step 1: 改算子允许集**
该类静态 map(算子→允许 dataType 集)里,给数值算子加 `"DECIMAL"`:
```java
m.put("GT",          Set.of("LONG", "DOUBLE", "DECIMAL"));
m.put("GTE",         Set.of("LONG", "DOUBLE", "DECIMAL"));
m.put("LT",          Set.of("LONG", "DOUBLE", "DECIMAL"));
m.put("LTE",         Set.of("LONG", "DOUBLE", "DECIMAL"));
m.put("BETWEEN",     Set.of("LONG", "DOUBLE", "DECIMAL", "DATE", "DATETIME"));
m.put("NOT_BETWEEN", Set.of("LONG", "DOUBLE", "DECIMAL", "DATE", "DATETIME"));
m.put("EQ",          Set.of("LONG", "DOUBLE", "DECIMAL", "STRING", "BOOLEAN", "DATE", "DATETIME"));
m.put("NEQ",         Set.of("LONG", "DOUBLE", "DECIMAL", "STRING", "BOOLEAN", "DATE", "DATETIME"));
```
(IN/NOT_IN 不加 DECIMAL,维持 LONG/STRING。)

- [ ] **Step 2: 测试 — DECIMAL metric 过数值算子校验**
在该类测试里加:构造一个 metricCode→"DECIMAL" 的 dataTypeMap + 一个 `GTE` ConditionNode,`AstDataTypeResolver.resolve(...)` 不抛、冻结 dataType="DECIMAL";再加一个 `IN` + DECIMAL 应被拒(抛校验异常),确认 DECIMAL 不污染非数值算子。
Run: `$MVN -pl rule-config-svc -am test -Dtest='AstDataTypeResolverTest' -Dsurefire.failIfNoSpecifiedTests=false` → PASS。

- [ ] **Step 3: 提交**
```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/AstDataTypeResolver.java rule-config-svc/src/test
git commit -m "feat(config): AstDataTypeResolver 数值算子允许 DECIMAL"
```

---

## Task 5: schema — metric_definition 三列 ENUM→VARCHAR(新迁移)

**Files:** Create `rule-config-svc/src/main/resources/db/migration/V1_6__metric_columns_enum_to_varchar.sql`。

> 用**新迁移**而非改 V1_0/V1_5(Flyway 已应用,改旧文件会校验和失配)。

- [ ] **Step 1: 写迁移**
```sql
-- ENUM 不友好(每加类型要 ALTER + 双重定义);改 VARCHAR,允许值校验上移 app。
ALTER TABLE metric_definition
  MODIFY COLUMN data_type   VARCHAR(32) NOT NULL,
  MODIFY COLUMN source_type VARCHAR(32) NOT NULL,
  MODIFY COLUMN status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
```

- [ ] **Step 2: 跑 config-svc 测试(Flyway 在测试启动期应用迁移)**
Run: `$MVN -pl rule-config-svc -am test 2>&1 | grep -E 'Tests run:.*Failures|BUILD|ERROR' | tail -6`
Expected: 迁移成功应用、Failures:0、BUILD SUCCESS(若有用 Testcontainers/内嵌库的集成测试,确认 V1_6 通过)。

- [ ] **Step 3: 提交**
```bash
git add rule-config-svc/src/main/resources/db/migration/V1_6__metric_columns_enum_to_varchar.sql
git commit -m "feat(config): metric_definition data_type/source_type/status ENUM→VARCHAR"
```

---

## Task 6: app 校验(允许值上移)+ 接受 DECIMAL

**Files:** Create `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/MetricEnums.java`;Modify `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/MetricWriteServiceImpl.java`;Test `MetricWriteServiceImplTest`(或新增)。

> ENUM 去掉后 DB 不再约束值,校验必须落 app,否则非法 data_type/source_type/status 会被持久化。

- [ ] **Step 1: 允许值常量(单一真相源)**
```java
package com.sstlfsj.rule.config.internal.domain;

import java.util.Set;

/** metric_definition 枚举列的允许值(ENUM 去除后由 app 校验;单一真相源)。 */
public final class MetricEnums {
    public static final Set<String> DATA_TYPES =
            Set.of("LONG", "DOUBLE", "DECIMAL", "STRING", "BOOLEAN", "LIST", "DATE", "DATETIME");
    public static final Set<String> SOURCE_TYPES =
            Set.of("ATTRIBUTE", "SQL_AGGREGATE", "EXTERNAL_HTTP", "STREAM");
    public static final Set<String> STATUSES = Set.of("ACTIVE", "DISABLED");
    private MetricEnums() {}
}
```

- [ ] **Step 2: 写失败测试**
在 `MetricWriteServiceImplTest`(无则建,沿用其既有 mock 风格)加:
```java
@Test void createMetric_invalidDataType_rejected() {
    // cmd.dataType()="FOO" → 抛 IllegalArgumentException;dataType="DECIMAL" 应通过
    assertThatThrownBy(() -> service.createMetric(tenantId, actorId, cmdWithDataType("FOO")))
            .isInstanceOf(IllegalArgumentException.class);
    assertThatCode(() -> service.createMetric(tenantId, actorId, cmdWithDataType("DECIMAL")))
            .doesNotThrowAnyException();
}
```
(按该测试已有的 cmd 构造/mock 方式适配;同样覆盖 sourceType 非法被拒。)

- [ ] **Step 3: 在写路径加校验**
`MetricWriteServiceImpl` 的 create/update 入口(设置 entity 前),加:
```java
if (!MetricEnums.DATA_TYPES.contains(cmd.dataType()))
    throw new IllegalArgumentException("非法 data_type: " + cmd.dataType());
if (!MetricEnums.SOURCE_TYPES.contains(cmd.sourceType()))
    throw new IllegalArgumentException("非法 source_type: " + cmd.sourceType());
```
(status 若由写服务设置/可外部传入,同样校验 `MetricEnums.STATUSES`;若 status 仅内部赋 ACTIVE/DISABLED 常量则可不校验——按实际赋值方式定。)

- [ ] **Step 4: 跑确认通过 + config-svc 全量**
Run: `$MVN -pl rule-config-svc -am test 2>&1 | grep -E 'Tests run:.*Failures|BUILD' | tail -3` → 全绿。

- [ ] **Step 5: 提交**
```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/MetricEnums.java rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/MetricWriteServiceImpl.java rule-config-svc/src/test
git commit -m "feat(config): metric 枚举列 app 校验(允许值上移,data_type 含 DECIMAL)"
```

---

## Task 7: 现存金额 metric 重判 DECIMAL(审计,需领域判断)

**Files:** 审计 `rule-config-svc`/`rule-app` 下的 metric 定义来源(seeder/测试 fixture/示例数据)。

- [ ] **Step 1: 找出当前数值 metric 定义**
```bash
grep -rn "DOUBLE" rule-config-svc/src rule-app/src docs/examples 2>/dev/null | grep -iE "data_?type|metric" | grep -v target
```
- [ ] **Step 2: 人工判定哪些是"金额/精确小数"语义** → 把这些 metric 的 dataType 由 `DOUBLE` 改为 `DECIMAL`(改定义/seed,无生产数据)。评分/计数/比率维持 DOUBLE。
- [ ] **Step 3: 若改了 seed/示例,重跑相关 config-svc 测试确认绿。**
- [ ] **Step 4: 提交**(若有改动)`git commit -m "chore(config): 金额类 metric 重判 DECIMAL"`。

> 注:若审计发现当前无具体金额 metric 定义在库(金额规则尚未落到 fixture),本 task 仅留结论:**新建金额 metric 时声明 DECIMAL**,无代码改动。

---

## Task 8: 全量回归 + 精度回归

- [ ] **Step 1: kernel + config-svc + eval-svc 全量**
Run: `$MVN -pl rule-kernel,rule-config-svc,rule-eval-svc -am test 2>&1 | grep -E 'Tests run:.*Failures|BUILD' | grep -v 'Time elapsed' | tail -6`
Expected: 全 Failures:0,BUILD SUCCESS。

- [ ] **Step 2: 确认精度回归**:现有数值算子测试(`GtEvaluatorTest`/`GteEvaluatorTest`/`LtEvaluatorTest`/`LteEvaluatorTest`/`BetweenEvaluatorTest`/`NotBetweenEvaluatorTest`/`EqEvaluatorTest`/`NeqEvaluatorTest`/`AbstractNumericEvaluatorTest`)全绿 = LONG/DOUBLE 改原始比较后结果与旧 BigDecimal 路径一致。

- [ ] **Step 3: 无新增提交**(纯验证)。

---

## Self-Review

**Spec 覆盖:** §2 类型三态→T1-3(策略)+T4(resolver)+T5/6(schema/校验);§3 边界(不截断回退/NaN 不命中/String/null)→T2/T3 测试;§4.1 VARCHAR→T5;§4.2 app 校验→T6;§4.3 resolver→T4;§4.4 工厂→T1-3;§4.5 策略→T1-3;§4.6 Default→T1;§4.7 金额重判→T7;§6 精度回归→T8。
**占位符:** 无 TBD;策略/工厂/迁移/校验/常量均给完整代码;T7 因依赖领域判断,给出审计机制 + 兜底结论(非占位)。
**类型一致:** `DecimalComparisonStrategy`(T1 改名)、`LongComparisonStrategy`/`DoubleComparisonStrategy`(T2/3)、`ComparisonStrategyFactory.forType` switch(T1→T2→T3 逐步拆 LONG/DOUBLE/DECIMAL)、`MetricEnums`(T6)全程一致;哨兵 `Integer.MAX_VALUE`/equals=false 沿用现状贯穿各策略。
