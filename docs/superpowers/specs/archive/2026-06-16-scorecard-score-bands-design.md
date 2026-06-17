# 评分卡分段决策（Score Bands）设计

> 日期：2026-06-16
> 缺口：评分卡（kind=SCORECARD）当前只是"加权布尔"——累加命中因子的 weight 得 score，`score >= threshold` 一刀切命中，输出 score 但 **decision/category 恒 null**，命中后只能由 EvalEngine 回退按最高 priority 的 decisionBinding 给单一决策。丢失了评分卡的核心语义：**分数分段 → 不同决策/风险等级**（FICO/信贷评分卡正统）。前端决策绑定编辑器有 `scoreRangeMin/scoreRangeMax` 两字段，但放错层（塞在通用 DecisionBinding 里），后端请求 DTO 不接收、直接丢弃——死字段。

## 1. 业界参照与方案定位

FICO/信贷评分卡（"评分卡"一词来源）：score 算出后过 **cutoffs/bands** 分段，每段映射一个 outcome（如 <640 Decline / 640-700 Refer / 700+ Approve），连续不重叠全覆盖。Camunda DMN 决策表则用 hit policy + completeness/overlap 校验工程化分段。

**方案定位（方式乙，经评审）**：`threshold`（命中门槛，可选）+ `bands`（命中后分段决策）两层。
- 不配 bands → 走现状单 threshold 逻辑（**完全向后兼容**）。
- 配 bands → score 落在某段则出该段决策；`threshold` 仍是"这条评分卡要不要表态"的门槛：`score < threshold` → 规则**不命中**（弃权，交场景内其他规则）。
- threshold 设为分数域下界 + bands 全覆盖 → 退化为 FICO 正统（恒命中、每分必落段出决策）。

方式乙是 FICO 正统的超集，且契合本引擎统一的 `ruleHit` 语义（AST 布尔/决策树/决策表均可不命中，评分卡"低于门槛不命中"与之对称）。

## 2. 数据模型（AST）

`ScorecardRootNode`（kernel）加 `bands`，保留 `threshold` 语义不变：

```java
public record ScorecardRootNode(
        List<ConditionNode> conditions,   // 不变：各带 weight 的因子
        double threshold,                 // 命中门槛：score < threshold → 规则不命中
        List<ScoreBand> bands             // 新增：分段决策（空=走现状单 threshold 逻辑）
) implements AstNode {
    public ScorecardRootNode {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        bands = bands == null ? List.of() : List.copyOf(bands);   // 缺键兜底空（引用类型，不触发 primitive 缺键 400）
    }
    /** 兼容构造：无 bands，现有 2 参调用点不变。 */
    public ScorecardRootNode(List<ConditionNode> conditions, double threshold) {
        this(conditions, threshold, List.of());
    }
}

/** 评分卡分段：score ∈ [minScore, maxScore) 时出 decisionCode（带 category）。 */
public record ScoreBand(
        double minScore,        // 含
        double maxScore,        // 不含（左闭右开）
        String decisionCode,    // 该段命中产出的决策码
        String category         // 风险等级标签（如 HIGH_RISK），可空
) {}
```

`ScoreBand` 不进 AstNode 多态体系（不是 AST 节点，是 ScorecardRootNode 的值对象），无需 @JsonSubTypes 注册。

## 3. 执行语义（ScorecardExecutor）

改 `ScorecardExecutor.execute`，**bands 空时一字不动走现状逻辑**：

1. 遍历 conditions 累加命中因子 weight 得 `score`（不变）。任一因子取数失败 → 整卡 ERROR（不变）。
2. **bands 为空**（现状路径）：`hit = score >= threshold`；返回 `EvalResult(hit, null, [], traces, null, score, null, null)`——行为与现状完全一致。
3. **bands 非空**（新路径）：
   - `score < threshold` → 规则不命中：`EvalResult(false, null, [], traces, null, score, null, null)`（弃权，仍带 score）。
   - `score >= threshold` → 找 `score ∈ [minScore, maxScore)` 的 band：
     - 命中某 band → 构造 `Decision(band.decisionCode, name?, priority?, rvId, code, version, band.category)`，返回 `EvalResult(true, decision, [decision], traces, null, score, band.category, null)`（**hitDecisions 非空**，复用 EvalEngine 既有"executor 自选决策"路径，零改 EvalEngine）。
     - 落在 bands 空隙（无匹配段）→ 命中但无段决策：`EvalResult(true, null, [], traces, null, score, null, null)`（回退到 EvalEngine 按 decisionBinding 给决策，与现状一致）。

> decisionBinding 与 bands 关系：bands 自带 decisionCode（评分卡专有，决策内联在 AST，与决策树叶子带 category 同构）。配 bands 后评分卡通常不再需要外层 decisionBindings；但 band 命中走 hitDecisions 路径不依赖 decisionBindings。Decision 的 name/priority 发布期回填（见 §4）。

## 4. 发布期校验与回填（PublishService 链路）

评分卡 kind 在发布期（resolveAndValidate / validateKindStructure）对 bands 增加校验：

1. **无重叠**：bands 按 minScore 排序后，相邻段 `prev.maxScore <= next.minScore`（左闭右开，端点相接不算重叠）。重叠 → 拒绝发布，明确错误信息（哪两段重叠）。
2. **min < max**：每段 `minScore < maxScore`，否则拒绝。
3. **decisionCode 存在**：每段 decisionCode 必须在 `decision_definition` 里存在（与决策树叶子 decisionCode 校验同款）；发布期回填 name/priority 进快照 Decision（运行期零查询，守 D6 不可变）。
4. **不强制全覆盖**：允许空隙（空隙=命中但该分数无段决策，回退 binding）。校验只保证无重叠 + decisionCode 合法，不要求首尾覆盖整个分数域（保留灵活性；信贷正统的全覆盖由配置者自行保证）。

回填机制：band 的 decisionCode → 查 decision_definition → 冻结 name/priority 进运行时快照（ScoreBand 运行期快照可携带回填后的 name/priority，或执行期 executor 从快照 decisionBindings 索引——倾向前者，与决策树叶子回填对称）。

## 5. 前端

**决策绑定编辑器**：删除 `scoreRangeMin/scoreRangeMax` 两个死字段（DecisionBindingEditor + 类型 DecisionBinding + i18n）——它们放错层，分段配置移到评分卡编辑器。

**评分卡编辑器（ScorecardEditor）**：在现有 threshold 之外，增加 **bands 配置区**：
- 一个动态表格：每行 = `[minScore, maxScore, decision(下拉选 decisionCode), category(可选文本)]`，可增删行。
- 前端轻校验：min < max、相邻段不重叠（即时提示），发布期后端再权威校验。
- bands 为空时评分卡退化为单 threshold（现状），UI 提示"不配分段则按阈值单命中"。

**类型**：`ScorecardRootNode` 前端类型加 `bands: ScoreBand[]`；`ScoreBand { minScore, maxScore, decisionCode, category }`。

## 6. 测试

- **kernel**：ScorecardExecutorTest 加——bands 空走老逻辑（回归不变）；bands 非空命中段出决策+category；score<threshold 不命中；落空隙命中无段决策；因子 ERROR 整卡 ERROR（不变）。ScoreBand record 测试。ScorecardRootNode 兼容构造器测试 + bands 缺键反序列化兜底空。
- **config-svc 发布期**：bands 重叠拒绝、min>=max 拒绝、decisionCode 不存在拒绝、合法 bands 回填 name/priority。
- **rule-api**：创建/编辑带 bands 的 scorecard 规则请求 DTO typed 传递（不转 String）。
- **前端**：构建通过；ScorecardEditor bands 增删 + 轻校验；DecisionBindingEditor 死字段已删。
- **功能 e2e（起服务）**：建评分卡（含 3 个 band：低/中/高分→不同 decision+category）→ 发布 → 评估不同分数主体验证落对应段出对应决策 + category；验 score<threshold 不命中；验老评分卡（无 bands）行为不变。

## 7. 非目标（YAGNI）

- **不做全覆盖强制**：允许空隙，校验只管无重叠 + decisionCode 合法。
- **不做 band 重叠的 hit policy**（DMN 那种 FIRST/PRIORITY 消歧）：直接禁止重叠，简单确定。
- **不动其他 kind**：AST_BOOLEAN/决策树/决策表/脚本不受影响。
- **不做分数归一化/WOE 分箱**：score 由因子 weight 累加，分箱算法是 metric/建模侧的事，不在评估引擎。
