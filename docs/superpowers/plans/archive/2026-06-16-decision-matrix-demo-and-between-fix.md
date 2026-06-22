# 决策矩阵录入 demo + 决策表 BETWEEN 修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复决策表 `BETWEEN`/`NOT_BETWEEN` 列运行时永不命中的潜伏 bug，并产出一个丢弃式 HTML mockup 证明"二维决策矩阵=录入便利、引擎模型不变"。

**Architecture:** Part 1 改 kernel 单点 `DecisionTableExecutor.buildParams`，给 BETWEEN/NOT_BETWEEN 增分支，把行条件二元 `List [lo,hi]` 映射成 `{min,max}`（`BetweenEvaluator` 期望的 key），TDD 先红后绿。Part 2 是纯客户端单 HTML mockup，矩阵视图实时展开成 `DecisionTableNode` JSON，不接引擎、不进 frontend 工程。

**Tech Stack:** Java 25 / JUnit5 + AssertJ（kernel 测试）；原生 HTML + JS（demo，无构建）。spec 见 `docs/superpowers/specs/2026-06-16-decision-matrix-demo-and-between-fix-design.md`。

---

## Task 1: 修复决策表 BETWEEN/NOT_BETWEEN params 映射（TDD）

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/DecisionTableExecutor.java`（`buildParams` + import）
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/DecisionTableExecutorTest.java`（新增用例 + metric 注入 helper）

- [ ] **Step 1: 写失败测试**

在 `DecisionTableExecutorTest` 末尾（最后一个 `}` 之前）加一个带 metric 的 ctx helper 和三个用例：

```java
    // 带 metric 的 EvalContext（端到端验证 BETWEEN 列取数→比较）
    private EvalContext ctxWith(String metric, Object value) {
        RuleEvent event = new RuleEvent("t1", "scene", "EVT", "u1",
                "e1", Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
        return new EvalContext("t1", event, null,
                Map.of(metric, new MetricValue(value, "UNKNOWN", "PROVIDED")),
                Instant.parse("2026-06-01T00:00:00Z"));
    }

    @Test
    void betweenColumn_buildsMinMaxParams() {
        // 列条件值为二元 List [lo,hi]，合成的 ConditionNode 必须带 min/max（不是 threshold）
        var col = new DecisionTableNode.Column("amount", "BETWEEN", "LONG");
        var row = new DecisionTableNode.Row(List.of(List.of(1000, 5000)), "REVIEW");
        DecisionTableNode ast = table(List.of(col), List.of(row));

        Map<String, Object>[] seen = new Map[1];
        ConditionEvaluator capturing = (n, c) -> { seen[0] = n.params(); return true; };

        EvalResult result = new DecisionTableExecutor(Map.of("BETWEEN", capturing))
                .execute(snapshot(ast, "REVIEW"), ctx());

        assertThat(result.ruleHit()).isTrue();
        assertThat(seen[0]).containsEntry("min", 1000).containsEntry("max", 5000);
    }

    @Test
    void betweenColumn_realEvaluator_inRangeHits_outOfRangeMisses() {
        var col = new DecisionTableNode.Column("amount", "BETWEEN", "LONG");
        var hitRow  = new DecisionTableNode.Row(List.of(List.of(1000, 5000)), "REVIEW");
        var missRow = new DecisionTableNode.Row(List.of(List.of(0, 1000)),    "PASS");

        var executor = new DecisionTableExecutor(Map.of("BETWEEN", new BetweenEvaluator()));

        // amount=2000 ∈ [1000,5000] → 命中第一行
        EvalResult hit = executor.execute(snapshot(table(List.of(col), List.of(hitRow)), "REVIEW"),
                ctxWith("amount", 2000));
        assertThat(hit.ruleHit()).isTrue();
        assertThat(hit.finalDecision().code()).isEqualTo("REVIEW");

        // amount=2000 ∉ [0,1000] → 不命中
        EvalResult miss = executor.execute(snapshot(table(List.of(col), List.of(missRow)), "PASS"),
                ctxWith("amount", 2000));
        assertThat(miss.ruleHit()).isFalse();
    }

    @Test
    void notBetweenColumn_realEvaluator_outOfRangeHits() {
        var col = new DecisionTableNode.Column("amount", "NOT_BETWEEN", "LONG");
        var row = new DecisionTableNode.Row(List.of(List.of(1000, 5000)), "BLOCK");

        var executor = new DecisionTableExecutor(Map.of("NOT_BETWEEN", new NotBetweenEvaluator()));

        // amount=8000 ∉ [1000,5000] → NOT_BETWEEN 命中
        EvalResult hit = executor.execute(snapshot(table(List.of(col), List.of(row)), "BLOCK"),
                ctxWith("amount", 8000));
        assertThat(hit.ruleHit()).isTrue();
        assertThat(hit.finalDecision().code()).isEqualTo("BLOCK");
    }
```

并在文件顶部 import 区加：

```java
import com.sstlfsj.rule.kernel.internal.condition.BetweenEvaluator;
import com.sstlfsj.rule.kernel.internal.condition.NotBetweenEvaluator;
```

- [ ] **Step 2: 跑测试确认失败（复现 bug）**

设置环境（mvn-env skill）后运行：

Run: `$MVN -pl rule-kernel -am test -Dtest=DecisionTableExecutorTest`
Expected: FAIL —— `betweenColumn_buildsMinMaxParams` 断言 `min`/`max` 不存在（实际 params 是 `{threshold=[1000,5000]}`）；两个 real evaluator 用例因 `min`/`max` 为 null 而 `ruleHit=false`，命中断言失败。

- [ ] **Step 3: 改 buildParams 增 BETWEEN/NOT_BETWEEN 分支**

`DecisionTableExecutor.java` 顶部 import 区加：

```java
import java.util.HashMap;
```

把 `buildParams` 改为（注意 Javadoc 同步）：

```java
    /**
     * 将行中的条件值转成 ConditionNode.params，遵循各算子约定：
     * 单值算子用 "threshold"，IN 类用 "values"，BETWEEN 类用 "min"/"max"（行条件值为二元 [lo,hi] List）。
     */
    private static Map<String, Object> buildParams(String operator, Object condValue) {
        return switch (operator.toUpperCase()) {
            case ConditionTypes.IN, ConditionTypes.NOT_IN -> Map.of(ConditionParams.VALUES, condValue);
            case ConditionTypes.BETWEEN, ConditionTypes.NOT_BETWEEN -> {
                // 行条件值约定为二元 List [lo, hi]；HashMap 容忍开区间端点为 null
                // （BetweenEvaluator 见 null 端点即不命中，与开区间语义一致）
                if (condValue instanceof List<?> bounds && bounds.size() == 2) {
                    Map<String, Object> p = new HashMap<>();
                    p.put(ConditionParams.MIN, bounds.get(0));
                    p.put(ConditionParams.MAX, bounds.get(1));
                    yield p;
                }
                yield Map.of();
            }
            default -> Map.of(ConditionParams.THRESHOLD, condValue);
        };
    }
```

（顺手把原 `"values"`/`"threshold"` 字面量换成 `ConditionParams.VALUES`/`THRESHOLD` 常量，封闭取值不散落字面量；`ConditionParams` 已随 `api.model.*` 导入。）

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-kernel -am test -Dtest=DecisionTableExecutorTest`
Expected: PASS（含新增 3 个用例 + 原有用例全绿）。

- [ ] **Step 5: 全量兜底**

Run: `$MVN clean test`
Expected: 所有模块 PASS（`clean` 强制重编译所有 test 类，避免增量漏过期）。

- [ ] **Step 6: 提交**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/DecisionTableExecutor.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/DecisionTableExecutorTest.java
git commit -m "fix(kernel): 决策表 BETWEEN/NOT_BETWEEN 列映射 min/max，修复永不命中"
```

---

## Task 2: 决策矩阵录入 demo（丢弃式单 HTML）

**Files:**
- Create: `docs/examples/decision-matrix-mockup.html`

- [ ] **Step 1: 创建完整 HTML 文件**

写入 `docs/examples/decision-matrix-mockup.html`，完整内容：

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>决策矩阵录入 mockup — rule-engine</title>
<style>
  body{font-family:-apple-system,Segoe UI,Helvetica,Arial,sans-serif;margin:20px;color:#24292f}
  h2{margin:0 0 4px} .sub{color:#57606a;font-size:13px;margin:0 0 16px}
  .wrap{display:flex;gap:14px;align-items:stretch}
  .panel{flex:1;border:1px solid #d0d7de;border-radius:8px;overflow:hidden}
  .ph{padding:7px 12px;font-size:13px;font-weight:600;color:#fff}
  .ph.l{background:#1f6feb} .ph.r{background:#1a7f37}
  .body{padding:12px;font-size:12px}
  .axis{background:#f6f8fa;border:1px solid #e1e4e8;border-radius:6px;padding:8px;margin-bottom:10px}
  .axis label{color:#57606a;display:block;margin-bottom:4px}
  .bucket{display:flex;gap:4px;align-items:center;margin:3px 0}
  .bucket input{width:64px} input,select{padding:3px 5px;border:1px solid #d0d7de;border-radius:4px;font-size:12px}
  table{border-collapse:collapse;margin-top:6px} td,th{border:1px solid #e1e4e8;padding:4px;text-align:center;font-size:12px}
  th{background:#fafbfc;color:#57606a;font-weight:500}
  pre{background:#0d1117;color:#c9d1d9;padding:12px;border-radius:0;margin:0;font-size:11px;line-height:1.5;overflow:auto;height:100%}
  .note{margin-top:10px;font-size:12px;color:#57606a}
  button{cursor:pointer;border:1px solid #d0d7de;background:#f6f8fa;border-radius:4px;padding:2px 8px}
</style>
</head>
<body>
<h2>决策矩阵录入 mockup</h2>
<p class="sub">左：二维矩阵填表；右：实时展开成现有 <code>DecisionTableNode</code> JSON（2 列 BETWEEN + m×n 行）。引擎模型 0 改动，矩阵只是录入视图。</p>
<div class="wrap">
  <div class="panel">
    <div class="ph l">矩阵视图</div>
    <div class="body" id="editor"></div>
  </div>
  <div class="panel">
    <div class="ph r">展开为 DECISION_TABLE（实时）</div>
    <pre id="json"></pre>
  </div>
</div>
<p class="note" id="note"></p>

<script>
const FEATURES = ['age', 'amount', 'risk_score'];
const DECISIONS = ['PASS', 'REVIEW', 'BLOCK'];

// 矩阵态：每轴 = 特征 + 闭区间分箱 [{label,lo,hi}]；cells 以 "yi_xi" 为键
const state = {
  y: { feature: 'age',    buckets: [ {label:'青年',lo:0,hi:30}, {label:'中年',lo:30,hi:60}, {label:'老年',lo:60,hi:120} ] },
  x: { feature: 'amount', buckets: [ {label:'低额',lo:0,hi:1000}, {label:'中额',lo:1000,hi:5000}, {label:'高额',lo:5000,hi:1000000} ] },
  cells: { '0_0':'PASS','0_1':'REVIEW','0_2':'BLOCK','1_0':'PASS','1_1':'PASS','1_2':'REVIEW','2_0':'REVIEW','2_1':'REVIEW','2_2':'BLOCK' },
};

function axisEditor(key) {
  const a = state[key];
  const opts = FEATURES.map(f => `<option ${f===a.feature?'selected':''}>${f}</option>`).join('');
  const rows = a.buckets.map((b,i) => `
    <div class="bucket">
      <input value="${b.label}" oninput="upd('${key}',${i},'label',this.value)" style="width:46px">
      <input type="number" value="${b.lo}" oninput="upd('${key}',${i},'lo',this.value)">–
      <input type="number" value="${b.hi}" oninput="upd('${key}',${i},'hi',this.value)">
      <button onclick="delBucket('${key}',${i})">−</button>
    </div>`).join('');
  return `<div class="axis">
    <label>${key.toUpperCase()} 轴特征</label>
    <select onchange="setFeature('${key}',this.value)">${opts}</select>
    <label style="margin-top:6px">分箱（label / [lo,hi)）</label>
    ${rows}
    <button onclick="addBucket('${key}')">+ 分箱</button>
  </div>`;
}

function grid() {
  const head = '<th></th>' + state.x.buckets.map(b => `<th>${b.label}</th>`).join('');
  const body = state.y.buckets.map((yb,yi) => {
    const cells = state.x.buckets.map((xb,xi) => {
      const k = yi+'_'+xi, v = state.cells[k] || '';
      const opts = DECISIONS.map(d => `<option ${d===v?'selected':''}>${d}</option>`).join('');
      return `<td><select onchange="setCell('${k}',this.value)"><option value=""></option>${opts}</select></td>`;
    }).join('');
    return `<tr><th>${yb.label}</th>${cells}</tr>`;
  }).join('');
  return `<table><tr>${head}</tr>${body}</table>`;
}

function expand() {
  const col = (ax) => ({ metricCode: ax.feature, operator: 'BETWEEN', dataType: 'LONG' });
  const rows = [];
  state.y.buckets.forEach((yb,yi) => state.x.buckets.forEach((xb,xi) => {
    rows.push({ conditions: [ [yb.lo,yb.hi], [xb.lo,xb.hi] ], decisionCode: state.cells[yi+'_'+xi] || '' });
  }));
  return { kind:'DECISION_TABLE', columns:[ col(state.y), col(state.x) ], rows };
}

function render() {
  document.getElementById('editor').innerHTML = axisEditor('y') + axisEditor('x') + grid();
  document.getElementById('json').textContent = JSON.stringify(expand(), null, 2);
  const n = state.y.buckets.length, m = state.x.buckets.length;
  document.getElementById('note').textContent =
    `引擎模型 0 改动 · FIRST_HIT · 2 列 BETWEEN × ${n}×${m}=${n*m} 行（行条件 [lo,hi] 经修复后的 buildParams 映射成 min/max）`;
}

// 交互回调
window.setFeature=(k,v)=>{state[k].feature=v;render()};
window.setCell=(k,v)=>{state.cells[k]=v;render()};
window.upd=(k,i,f,v)=>{state[k].buckets[i][f]=(f==='label')?v:Number(v);render()};
window.addBucket=(k)=>{state[k].buckets.push({label:'新箱',lo:0,hi:0});render()};
window.delBucket=(k,i)=>{if(state[k].buckets.length>1){state[k].buckets.splice(i,1);render()}};
render();
</script>
</body>
</html>
```

- [ ] **Step 2: 手动验证**

用浏览器打开文件（macOS）：

Run: `open docs/examples/decision-matrix-mockup.html`
Expected:
- 左侧出现 Y 轴(age)/X 轴(amount) 分箱编辑 + 3×3 决策网格；右侧 JSON 含 `columns`(2 列 BETWEEN) + `rows`(9 行，每行 `conditions:[[lo,hi],[lo,hi]]`)。
- 改一个格子的决策 → 右侧对应行 `decisionCode` 实时变。
- 给 X 轴点"+ 分箱" → 网格多一列、右侧 rows 变 3×4=12 行、底注行数同步。
- 改某分箱的 lo/hi → 右侧对应行 `conditions` 区间实时变。

（UI mockup 无自动化测试——按测试纪律明示"未亲自跑自动化断言，靠上述手动核对"。）

- [ ] **Step 3: 提交**

```bash
git add docs/examples/decision-matrix-mockup.html
git commit -m "docs(examples): 加决策矩阵录入 mockup（矩阵视图→DecisionTableNode 展开）"
```

---

## Self-Review 记录

- **Spec coverage**：Part 1 修复（Task 1）+ Part 2 demo（Task 2）+ 测试（Task 1 Step 1/4/5、Task 2 Step 2）均有对应任务，覆盖 spec §2/§3/§5。
- **Placeholder**：无 TBD/TODO；测试与 buildParams 代码完整给出；HTML 全文给出。
- **Type 一致**：`DecisionTableNode.Column(metricCode, operator, dataType)` 三参构造、`Row(List conditions, decisionCode)`、`MetricValue(value, dataType, valueSource)`、`ConditionParams.MIN/MAX/VALUES/THRESHOLD`、`BetweenEvaluator`/`NotBetweenEvaluator` 均与现有代码签名一致；demo expand() 产出的 JSON 字段名与 kernel 模型一致。
