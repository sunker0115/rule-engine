# DECIMAL 数值类型 + 类型分派比较 设计

> Status: 设计待批准(2026-06-08)。async-profiler 摸排 backlog #1。`NumericComparisonStrategy` 对**每次**数值比较无条件把两操作数 coerce 成 BigDecimal(且走 `new BigDecimal(n.toString())` 的 String 往返),含整型——摸排实测 BigDecimal 占 ~7.4% 分配 + 部分 String churn。引入显式数值三态 LONG/DOUBLE/DECIMAL,按类型分派:整型/浮点走原始比较(零 BigDecimal),仅 DECIMAL 走 BigDecimal。对标 Aviator(long→bigint→decimal→double)、SpEL(关系运算按类型提升)。greenfield 无生产数据,放手重构。

## 1. 动机
- `ComparisonStrategyFactory.forType(dataType)` 现把 **LONG 和 DOUBLE 都路由到 `NumericComparisonStrategy`(BigDecimal)**;`toBigDecimal` 对 Number 走 `new BigDecimal(n.toString())` → 每次比较 2×BigDecimal + 2×String + 2×解析,× 50 候选 × 高 QPS。
- BigDecimal 对 LONG 纯属多余(`Long.compare` 精确);对 DOUBLE 也只是忠实比较 double 的十进制展开,`Double.compare` 同结果且零分配。BigDecimal 的价值只在**精确小数(金额/scale)**。
- 摸排同时证明:eval 计算仅 ~1% CPU,本优化目标是**降分配/平 p99**,非提 CPU。

## 2. 类型模型(比较精度语义的最小完备集)
- **LONG**:任意整型(byte/short/int/long 无损归一);`Long.compare` 精确。
- **DOUBLE**:任意浮点(float 无损归一);接受 IEEE 语义;`Double.compare`。
- **DECIMAL**:精确小数 / 超 long 范围整型;BigDecimal。
- 不引入 BYTE/SHORT/INT(LONG 子集,比较冗余)、不引入 BigInteger(DECIMAL 覆盖)。

## 3. 决策(已批准)
| # | 决策 | 选择 |
|---|---|---|
| 类型不匹配(3-a) | dataType=LONG 但 actual 是小数等 | **不截断;fast-path 仅在运行时类型命中时走原始比较,否则回退 Decimal（BigDecimal）** —— SpEL/Aviator 的 widening 思想,绝不 narrowing/截断(截断会翻转比较结果) |
| NaN/Infinity(3-b) | DOUBLE 比较遇 NaN/∞ | **显式判 NaN/Infinity → 哨兵/不命中**,**不**沿用 `Double.compare` 的"NaN 最大"全序(否则坏数据会误触发 `>` 规则);与现状哨兵语义一致 |
| String 操作数 | params 里 threshold 是 String | 各策略尝试解析(parseLong/parseDouble);失败 → 哨兵/不命中(同现状) |
| null | actual/operand 为 null | 不命中(同现状) |
| 金额误标 DOUBLE | footgun | DECIMAL 是"我要精确"的**显式类型选择**(同 SQL DOUBLE vs DECIMAL / Aviator double vs `M`);文档说明,接受 |

## 4. 组件改造

### 4.1 DB:`data_type` 由 ENUM 改 VARCHAR(rule-config-svc)
- `data_type` 现为 `ENUM('LONG','DOUBLE','STRING','BOOLEAN','LIST')` —— 每加类型要 ALTER + 值在 schema/app 双重定义,不友好。改为 **`VARCHAR(32) NOT NULL`**:允许值改由**应用层单一定义**,DB 不约束。
- greenfield:直接改 `V1_0__init_schema.sql` 建表为 VARCHAR(无数据,无需独立 ALTER 迁移)。
- **不加 CHECK 约束**(否则"加类型要 ALTER"又回来了);靠 app 校验 + app 为唯一写入方。
- 收益:本次加 DECIMAL、及将来任何数值/类型新增 = **纯代码改动,零 DB 迁移**。
- **一并改同表另两个 ENUM**(一致性,本期顺带):`source_type ENUM('ATTRIBUTE','SQL_AGGREGATE','EXTERNAL_HTTP','STREAM')` → `VARCHAR(32)`;`status ENUM('ACTIVE','DISABLED')` → `VARCHAR(16)`。允许值同样上移到 app 校验。范围限 `metric_definition` 本表(不做全库 ENUM 清扫)。

### 4.2 config-svc 校验:应用层枚举为单一真相源
- metric 创建/更新校验 `data_type ∈ 允许集`;允许集由一处 Java 定义(与 kernel `AstDataTypeResolver` 算子→类型表同源,加 `DECIMAL`)。若已有 DataType 枚举/常量则加 DECIMAL,否则建一个集中常量。非法值 → 校验拒绝(原 ENUM 的约束职责上移到 app)。

### 4.3 `AstDataTypeResolver`(kernel)
- 算子→允许 dataType 表:给 `GT/GTE/LT/LTE/BETWEEN/NOT_BETWEEN/EQ/NEQ` 的集合加 `"DECIMAL"`。`IN/NOT_IN` 维持(LONG/STRING)。

### 4.4 `ComparisonStrategyFactory.forType`(kernel)
- `LONG → LongComparisonStrategy`、`DOUBLE → DoubleComparisonStrategy`、`DECIMAL → DecimalComparisonStrategy`、其余/`null → DefaultComparisonStrategy`(不变)。返回缓存单例(同现状,非热点)。

### 4.5 策略类(kernel internal.condition.strategy)
- **`DecimalComparisonStrategy`**:把现有 `NumericComparisonStrategy` 改名(逻辑不变:`toBigDecimal` via toString + `compareTo`)。承担 DECIMAL 路径 + 各 fast-path 的回退。
- **`LongComparisonStrategy`**(新):
  - `actual` 与 `operand` 都"整型可表"(Long/Integer/Short/Byte;String 可 `Long.parseLong`)→ `Long.compare(a.longValue(), b)`。
  - 任一非整型(Double/Float/BigDecimal/含小数 String)或解析失败 → **回退 `DecimalComparisonStrategy`**(不截断)。
  - null → 哨兵/不命中。
- **`DoubleComparisonStrategy`**(新):
  - `actual`/`operand` 任一 `Double.isNaN`/`isInfinite` → 哨兵/不命中(显式)。
  - 都可安全表为 double(普通 Number;String 可 `Double.parseDouble`)→ `Double.compare`。
  - BigDecimal 操作数 / 超 2^53 精度风险 → 回退 `DecimalComparisonStrategy`。
  - 解析失败/null → 哨兵/不命中。
- 哨兵约定沿用现状:`compare` 不可比时返回 `Integer.MAX_VALUE`,`equals` 返回 false(各数值算子据此判不命中)。

### 4.6 `DefaultComparisonStrategy`(dataType=null,DSL 兜底)
- 内部 `NUMERIC` 引用改指向 `DecimalComparisonStrategy`(行为不变,untyped 走精确 BigDecimal)。不为 DSL 兜底加 fast-path(YAGNI)。

### 4.7 现存金额规则重判(greenfield)
- 把当前建成 DOUBLE 的金额 metric 定义改判 `DECIMAL`(改 dev 定义,无数据迁移)。哪些是金额 = 人工领域判断。

## 5. 数据流
```
发布期: metric_definition.data_type(含 DECIMAL) → AstDataTypeResolver 冻结 ConditionNode.dataType
运行期: 数值算子(Gt/Gte/...).evaluate → ComparisonStrategyFactory.forType(dataType)
        ├ LONG    → LongComparisonStrategy   (整型→Long.compare;非整型→回退 Decimal)
        ├ DOUBLE  → DoubleComparisonStrategy (NaN/∞→不命中;否则 Double.compare;精度风险→回退 Decimal)
        ├ DECIMAL → DecimalComparisonStrategy(BigDecimal)
        └ null    → DefaultComparisonStrategy(按运行时类型,Number→Decimal)
```

## 6. 测试
- `LongComparisonStrategy`:整型 fast-path(Long/Integer/String)正确;**actual=3.7(声明 LONG)→ 回退 BigDecimal,结果与旧路径一致(不截断、不翻转)**;String 整型解析;解析失败/null → 不命中。
- `DoubleComparisonStrategy`:double 比较;**NaN/Infinity → 不命中**;BigDecimal 操作数 → 回退;String 浮点解析;失败/null → 不命中。
- `DecimalComparisonStrategy`(原 Numeric 测试迁移):精确比较 + scale 忽略(50000.00==50000)不变。
- `ComparisonStrategyFactory`:LONG/DOUBLE/DECIMAL/null 路由到对应策略。
- `AstDataTypeResolver`:DECIMAL 对数值算子通过校验;对 IN 等不兼容算子按现状拒绝。
- config-svc:metric `data_type=DECIMAL` 创建/更新通过校验。
- **精度回归**:现有数值算子测试(Gt/Gte/Lt/Lte/Between/NotBetween/Eq/Neq)全绿——LONG 路径结果不变;DOUBLE 路径对同一组 double 比较结果与旧 BigDecimal 路径一致。
- 迁移:fresh DB 的 metric_definition.data_type 含 DECIMAL。
- 回归:kernel + config-svc + eval-svc 全量绿。

## 7. 非目标
- BigInteger 独立类型(DECIMAL 覆盖);BYTE/SHORT/INT(LONG 覆盖)。
- threshold 预编译/缓存(LONG/DOUBLE 原始比较已廉价,operand 解析极便宜,YAGNI)。
- 按窄类型做输入范围校验(与"比较语义"正交,另议)。
- DSL/null 兜底路径的 fast-path(保 BigDecimal 安全)。
- 金额误标 DOUBLE 的强制拦截(显式类型语义 + 文档,不在本期加校验)。

## 8. native / 风险
- 纯 kernel + config-svc,无新反射;`data_type` 改 VARCHAR 是建表脚本调整(greenfield 无数据迁移),允许值校验在 app。
- 主要风险:fast-path 改变现存数值规则比较结果 → §6 精度回归(现有算子测试全绿)兜底;边界(类型不匹配/NaN/String/null)逐一测试对齐现状哨兵语义。
