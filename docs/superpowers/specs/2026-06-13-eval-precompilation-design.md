# §2.13 评估期预编译(纯编译版)设计

> 日期:2026-06-13。来源:D20 / 08-evolution.md §2.13。范围:评估期把布尔 AST 编译为 `Predicate<EvalContext>` 闭包,替换解释器树遍历。**本轮不含 alpha/CSE 共享**(独立后续)。
>
> 背景评估见 `docs/superpowers/plans/2026-06-13-b1-b3-gap-assessment.md`。

## 1. 目标与非目标

**目标**:在不改倒排索引、SPI、取数、决策合成、DB schema、API 的前提下,把 `AST_BOOLEAN` kind 规则的求值从"每次评估递归 walk AST + 节点分派"换成"一次编译成嵌套闭包、之后每次评估直接 `predicate.test(ctx)`",消除节点分派与 trace 分配开销。

**非目标**:
- 不做 alpha/CSE 跨规则去重(乘法优化,独立后续轮)。
- 不动 Scorecard / DecisionTable 执行器(只碰布尔执行器)。
- 不改 trace 语义、不改灰度以外的任何运行时行为。

**成功判据**:
1. 编译版布尔结果与 trace 输出与解释器**逐条/逐行一致**(平价测试 + 端到端 trace 对比)。
2. 编译版 per-eval 分配 **≤ 解释器**。说明:Phase 0 实测(冻结 LONG)显示解释器非 trace 快路径已被 JIT 逃逸分析降到 ~72–120 B/op(`ConditionOutcome` 与 Long 装箱被标量替换),故"零分配"不是现实目标;编译版的结构价值在**编译期绑定 evaluator**,在生产巨态分派(多算子/多形状)下逃逸分析退化时,分配与分派比解释器更稳。
3. 编译版在高条件数场景 AST 求值耗时低于解释器(省节点 `switch` 分派);是否 ship(灰度开)由 §9 的 Task 8 A/B 实测决定。

**Phase 0 闸结论(2026-06-13)**:冻结 LONG 下 AST 求值 79 ns(5 条件)/865 ns(50 条件),分配 72–120 B/op。providedMetrics 主导场景下收益成立但边际,故编译版**默认关**,作为架构层可切换能力落地,实测达标后再灰度开。

## 2. 范围边界

只替换 `AST_BOOLEAN` 求值方式,涉及 `sealed AstNode` 五种节点:`AndNode` / `OrNode` / `NotNode` / `XorNode` / `ConditionNode`。其余节点(`ScorecardRootNode` / `IfNode` / `DecisionTableNode` / `DecisionLeafNode`)归各自执行器,不在本设计范围。

**全不动清单**:`SceneRuleIndex` 倒排索引、`ConditionEvaluator` SPI 及全部实现、`EvalContextAssembler` 取数、Pre-Gate / 决策合成 / API 层、DB schema(`compiled_predicate_ref` 列保持预留留空)、`EvalEngine`(见 §4,零改动)。

## 3. 编译技术:闭包组合(方案 A)

递归把 AST 编译为嵌套 `Predicate<EvalContext>`:

```java
Predicate<EvalContext> compile(AstNode n) {
    return switch (n) {
        case ConditionNode c -> {
            // 编译期解析并捕获 evaluator(巨态分派下比每次查 map 更稳);
            // satisfiesBoolean 镜像解释器叶子的布尔投影(metric ERROR/无算子 → false),不分配 ConditionOutcome
            ConditionEvaluator ev = evaluators.get(c.conditionType());
            yield ctx -> ConditionEvaluation.satisfiesBoolean(c, ctx, ev);
        }
        case AndNode a -> {
            Predicate<EvalContext>[] ps = compileChildren(a.children());
            yield ctx -> { for (int i = 0; i < ps.length; i++) if (!ps[i].test(ctx)) return false; return true; };
        }
        case OrNode o -> {
            Predicate<EvalContext>[] ps = compileChildren(o.children());
            yield ctx -> { for (int i = 0; i < ps.length; i++) if (ps[i].test(ctx)) return true; return false; };
        }
        case NotNode no -> { var p = compile(no.child()); yield ctx -> !p.test(ctx); }
        case XorNode x -> {
            Predicate<EvalContext>[] ps = compileChildren(x.children());
            // 语义必须逐字镜像 InterpretedExecutor.evalXor —— 落地时以该方法为准,不另立语义
            yield ctx -> { int t = 0; for (int i = 0; i < ps.length; i++) if (ps[i].test(ctx)) t++; return /* evalXor 真值条件 */ xorTruth(t, ps.length); };
        }
        default -> throw new IllegalArgumentException("非布尔节点不可编译: " + n.getClass());
    };
}
```

**零分配不变量(强制)**:组合节点子项一律**编译期收数组**(`compileChildren` 返回 `Predicate[]`)、**求值期下标循环**,禁用 `for (var p : list)` enhanced-for(会 per-eval 分配 `Iterator`)。`test()` 返回原始 `boolean`,不装箱。

**为什么不用 LambdaMetafactory / 字节码生成**:
- LambdaMetafactory 从动态 AST 树拼装实为 `MethodHandle` 组合子,复杂度高、JIT 内联未必更好、运行期 spin hidden class、难调试——理论收益没有,实际成本更高。
- 字节码生成(Janino/ASM,整树编成单 `test()` 方法)理论最快但 codegen + 类加载 + 调试 + 沙箱全是负担。
- 闭包组合零依赖、零类加载、可断点,拿走"解释器→编译"80% 收益。A 唯一潜在残余代价是 `ps[i].test()` 调用点**巨态分派**导致 JIT 无法内联(速度问题,非分配问题);若 benchmark 证明此为瓶颈,再升字节码生成,`CompiledExecutor` 对外接口不变。

## 4. 组件清单

### 新建 —— `rule-kernel`

| 组件 | 包 | 职责 |
|---|---|---|
| `AstCompiler` | `internal/compiler` | `Predicate<EvalContext> compile(AstNode)` 递归(§3)。叶子按 `conditionType` 查 `ConditionEvaluator` 并捕获。遇非布尔节点抛 `IllegalArgumentException`(发布期不变量,理论不达)。 |
| `RuleVersionCache` | `internal/compiler` | `ConcurrentHashMap<Long, Predicate<EvalContext>>` 按 `ruleVersionId` 缓存。`computeIfAbsent` lazy 编译;`retainOnly(Set<Long> activeIds)` / `evict(long id)`。 |
| `CompiledExecutor` | `internal/evaluator` | 实现 `RuleVersionExecutor`,内部持有 `InterpretedExecutor` 当兜底 + `AstCompiler` + `RuleVersionCache` + 灰度配置。逻辑见 §5。 |

### 新建 —— `rule-eval-svc`

| 组件 | 包 | 职责 |
|---|---|---|
| `CompiledPredicateEvictor` | `internal/listener` | 监听索引热更(`RulePublishedEvent` / `SceneChangedEvent`),调 `cache.evictAll()` 清空编译产物。 |

**缓存键不可变免脏(判断点 A)**:键用 `ruleVersionId`。发布版本不可变,同 id 永远同 AST,缓存**永不脏**——失效纯属内存卫生(清已下线版本释放内存),非正确性问题。正因永不脏,失效用 **`evictAll()` 全清**即可(发布事件低频 + lazy 重编译,下次评估按需重编),无需给 kernel 索引加"枚举活跃 id"方法做精确 set diff。

## 5. CompiledExecutor 执行逻辑

```
execute(snapshot, ctx):
    若 灰度未命中(disabled,或白名单非空且 snapshot.code 不在其中):
        → 委托 interpreter.execute(snapshot, ctx)          // 行为与今天逐字节一致
    若 TraceScope.COLLECT == true:
        → 委托 interpreter.execute(snapshot, ctx)          // trace 走解释器,见判断点 B
    否则(灰度命中 + 非 trace 快路径):
        predicate = cache.computeIfAbsent(snapshot.ruleVersionId(),
                                          id -> compileOrFallback(snapshot))
        return new EvalResult(predicate.test(ctx), List.of())   // 空 trace
```

**Trace 兼容(判断点 B,本设计关键招)**:编译版只服务非 trace 布尔快路径;开 trace 即回落解释器。理由:trace 是低频诊断慢路径,编译它零收益且要造平行的产 trace 编译产物(过度设计)。后果:编译器**完全不碰 `NodeTrace`**;trace 语义 100% 不变(永远走解释器)→ D7"每条规则各写一行 trace"自动满足,**切换前后 trace 必然逐行一致**。

## 6. 灰度切换 —— EvalEngine 零改动

不改 `EvalEngine`。装配时把 `AST_BOOLEAN` 这一格的执行器从 `InterpretedExecutor` 换成 `CompiledExecutor(interpreter, compiler, cache, config)`(装配点 = 现构造 `InterpretedExecutor` 并放入 `Map<String, RuleVersionExecutor>` 的 `@Configuration`,落地时定位)。开关全在 `CompiledExecutor` 内判:

```yaml
engine.rule.eval.compiled-executor:
  enabled: false           # 默认关 = 行为与今天逐字节一致(永远委托解释器)
  rule-code-whitelist: []  # enabled 且空 = 全量编译;非空 = 仅列出的 code 走编译
  on-compile-error: FALLBACK  # FALLBACK = WARN+回落解释器;FAIL = 抛异常中止(见 §7)
```

绑定 `@ConfigurationProperties("engine.rule.eval.compiled-executor")`。回退 = `enabled: false`,瞬时,不动数据/索引。

**灰度流程**:先 `enabled: true` + 少量 code 白名单 → 对比 trace 输出与解释器逐行一致 → 清空白名单全量切。出问题关开关退回解释器。

## 7. 错误处理 —— 由 `on-compile-error` 配置决定

**编译期**异常(结构性,罕见)按配置处置:
- `FALLBACK`(默认):WARN 日志 → 该 `ruleVersionId` 缓存一个回落哨兵,永久委托解释器,单条坏规则不拖垮评估。"编译版永不劣于解释器"。
- `FAIL`:抛 `IllegalStateException`(带 ruleVersionId/code)中止。语义是"AST 编译失败本应被发布期拦住,运行期遇到宁可炸不静默"。

**求值期**异常:两种策略下都不 catch,与解释器逐字节同行为(同一套 evaluator,异常传播路径一致)。

安全性质:默认 `FALLBACK` 下开编译开关,最坏退回解释器,**绝不比今天更糟**;需要"编译失败即暴露"时切 `FAIL`。

## 8. 测试

| 层 | 内容 |
|---|---|
| `AstCompilerTest` | 五种节点(And/Or/Not/Xor/Condition)编译布尔正确;**平价测试**:一批随机/深嵌套 AST,同 ctx 下 `compile(ast).test(ctx)` 与 `InterpretedExecutor` 结果逐条一致(核心正确性保证)。 |
| 零分配不变量 | JMH `-prof gc`(或轻量分配计数)断言编译热路径 ~0 alloc/op。 |
| `CompiledExecutorTest` | trace 模式委托解释器(有 NodeTrace);非 trace 走编译(空 trace);disabled 委托;白名单门控命中/未命中;编译异常回落解释器。 |
| `RuleVersionCacheTest` | computeIfAbsent 只编一次;evict / retainOnly;并发访问。 |
| `CompiledPredicateEvictorTest` | 索引变更后掉线 id 被清、活跃 id 保留。 |

## 9. Benchmark 闸(Phase 0,先行)

落地**第一步**用 `rule-benchmark`,在高候选规则数场景测 **AST 求值占端到端时延比例**:
- 占比 **>20%** → 编译收益真实,继续 §2–8。
- 占比 **<5%**(被取数淹没)→ **停**,把工作量挪去优化取数,而非编译 AST。
- 5%–20% → 据绝对 QPS 与 CPU 成本判断,记录数据后再定。

收尾再跑 A/B:解释器 vs 编译版的时延 + 分配 + trace 平价,确认成功判据(§1)全绿。

## 10. 改动量与不动量

| 范围 | 代码量 | 模块 |
|---|---|---|
| `AstCompiler` | ~120 行 | rule-kernel |
| `CompiledExecutor` | ~90 行 | rule-kernel |
| `RuleVersionCache` | ~60 行 | rule-kernel |
| `CompiledPredicateEvictor` | ~50 行 | rule-eval-svc |
| 灰度配置 + 装配 | ~40 行 | rule-eval-svc / rule-app |
| benchmark(Phase 0 + 收尾 A/B) | ~120 行 | rule-benchmark |
| 测试 | ~300 行 | rule-kernel + rule-eval-svc |
| **合计** | **~560 行新代码 + ~300 行测试** | |

骨架不动的依据:已有 `RuleVersionExecutor` SPI + 不可变快照 + `compiled_predicate_ref` 预留列 + `ExecutorRegistry`(executors map)四者把路铺好。本设计本质 = 写一个新 `RuleVersionExecutor` 实现 + 一个编译工具类 + 一个缓存层 + 一个失效监听器,纯增量。

## 11. 后续轮次(本设计不含)

- **alpha/CSE 共享**:同 `(sceneCode, eventType)` 下跨规则 `ConditionNode` hash 去重,一次 `EvalContext` 内同条件只算一次(乘法优化,~200 行可拆)。依赖本轮编译版落地并量化后再评估。
- **字节码生成升级**:仅当 benchmark 证明闭包组合的巨态分派成瓶颈时启动。
