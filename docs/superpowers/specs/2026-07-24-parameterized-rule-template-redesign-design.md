# D74 参数化规则模板(重设计)— 设计文档

## Context

D74 参数化规则模板首版已实现(`rule_template` 表 + Service + Controller + 前端页面),但设计有根子缺陷,现按架构视角重做,**模板代码全部重写,不打补丁**。

**首版缺陷(为什么重做)**:占位符 `{"$slot":"key"}` 直接埋进 `ConditionNode.params` 的 body JSON 里,把三件本该解耦的事揉成一件——参数 schema、绑定位置、模板骨架。后果:

- 绑定位置靠**扫描发现**而非声明,导致 `collectSlotKeys`(校验用,遍历 typed AST 树、`default` 分支丢掉 `IfNode`/`DecisionTableNode`/`ScorecardRootNode`)与 `replaceSlotsInMap`(实例化用,遍历裸 Map 树、命中全部 `$slot`)**结构性不对称**——实测 DECISION_TREE/DECISION_TABLE 深层 slot 校验漏检。
- 占位符模型锁死在"AST params 值位"这一 AST 专属形态,无法自然表达 Script / Flow,"支持 4 kind"名不副实。
- kind 边界靠 `instanceof AstBody` throw 散在 5 处(create/update/instantiate/exportFromRule/validateSlotsConsistent),非类型或单点收口。

**本轮定位(已对齐)**:

- **A 快照式**:实例化 = 盖章生成独立 `RuleVersion`,模板后续改动不回溯已生成实例,守 D6 不可变。模板是纯 authoring 便利层。**不做 B 活链接**(规则引用模板、模板改动传播——撞 D6/D60 红线)。
- **甲:设计做对、代码按新架构落地,功能开关默认关闭**(`rule.template.enabled=false` + 前端 `FEATURES.templates=false`)。当前无真实业务触发场景,不为未到的需求买单;但既然代码保留,就让它的设计正确而非打补丁,待真实场景出现直接开启。
- **覆盖全部 6 种现存 kind**(不留空壳):AST 四种(AST_BOOLEAN/SCORECARD/DECISION_TREE/DECISION_TABLE)+ EXPRESSION_SCRIPT + DECISION_FLOW。未来**新增** kind 时才加实现。
- **一处有意的 kernel/eval 增强**:为消除 Script/Flow 表达式"文本参数化"的异类性,引入通用 `params` 冻结常量命名空间(详见下文)。除此之外 kernel/eval 零改动。

## 现状(重构起点)

首版已落地、已加开关默认关闭,但 DB 迁移**未 apply**(经核:`flyway_schema_history` 最高到 1.41,`V1_42__rule_template.sql` 未执行,`rule_template` 表不存在)。因此本轮**直接改写 `V1_42`**,表结构一次成型,不加 V1_43、不留"先 token 后 bindings"的迁移史。

## 目标架构

三件事解耦,body 骨架**任何时候都是一个合法 body**(默认值就位,可直接校验/预览/dry-run);slot 与 binding 是旁挂 sidecar。所有 6 种 kind 的 body 都是 JSON 结构体,统一用 `JsonPointer` 寻址。

### 数据模型

```
RuleTemplate {
  code, tenantId, name, description,
  kind         : RuleKind             // 6 种之一;区分 AstBody 内部 4 种 AST kind
  bodySkeleton : RuleBody             // 合法 body,可覆盖位置填默认值,无任何 token
  slots        : List<TemplateSlot>   // 参数 schema
  bindings     : List<SlotBinding>    // slot → body 位置的显式绑定(sidecar)
  version, status : RuleTemplateStatus
}

TemplateSlot { key, label, dataType : DataType, required, constraint : SlotConstraint }
SlotConstraint { min, max, enumValues }

SlotBinding { slotKey, target : SlotTarget }

// sealed,Jackson 多态判别(仿 RuleBody / AstNode);单 permit,保留多态外壳给未来非 JSON 寻址的 target
sealed interface SlotTarget permits JsonPointerTarget
record JsonPointerTarget(String jsonPointer)   // 如 /conditionAst/children/0/params/threshold
```

**关键设计点**:

- **`dataType` 复用 kernel `DataType`**(`LONG/DOUBLE/DECIMAL/STRING/BOOLEAN/DATE/DATETIME/LIST`,排除运行时哨兵 `UNKNOWN`),不新造 `SlotDataType`——单一真相源,与 AST 冻结的 dataType 同集。
- **`TemplateSlot` 无 `defaultValue` 字段**:默认值 = `bodySkeleton` 在该 slot 对应 binding 位置的当前值(skeleton 本就必须合法、每个可覆盖位置都有真实值)。消除"两个默认值漂移"的 bug 类。`required=true` = 实例化必须提供(skeleton 值仅保证可预览);`required=false` = 可省、回落 skeleton 值。
- **`SlotTarget` 保持 sealed + 单 permit `JsonPointerTarget`**(不塌成裸字符串字段):bindings 落 JSON 列,保留多态外壳(带 `type` 判别)后,将来若出现"非 JSON 结构、无法 JsonPointer 寻址"的 body 形态,只需加 permit + `@JsonSubTypes` 一行的纯增量,不破坏已存 `JsonPointerTarget` 的 JSON 格式。与 `RuleBody`/`AstNode` 定式一致。
- **slot ↔ binding 严格 1:1 双射**:一个 slot 对应一个 binding 对应一个 skeleton 位置。v1 不支持"一 slot 填多个位置"(注为限制,真需要时再放开)。
- **`bodySkeleton` 不含任何占位符 token**——它是填了默认值的正常 body。"哪些位置可被 slot 覆盖"完全由 `bindings` 声明。**扫描机制彻底移除**,`collectSlotKeys`/`replaceSlots` 不对称从结构上不可能再发生。

### 统一寻址:JsonPointerTarget

`AstBody` / `ScriptBody` / `FlowBody` 都序列化为 JSON,`JsonPointer` 能寻址任意 JSON 位置,故**一种 target 覆盖全部 6 种 kind**:

- AST 四种:`/conditionAst/children/0/params/threshold`(JsonPointer 不区分 And/Scorecard/DecisionTable/IfNode,寻址任意深度)——首版"4 kind 内部不对称"当场消除。
- EXPRESSION_SCRIPT:`/script/params/threshold`(见下文 `params` 命名空间)。
- DECISION_FLOW:`/flowGraph/nodes/0/ruleCode`(选被引规则)、`/flowGraph/nodes/2/decisionCode`、`.../caseKeys`、`/flowGraph/params/threshold`(flow 表达式常量)。

`bind` = 在 body 的 JSON 树替换该 pointer 位置的值 → 反序列化回 `RuleBody`。逻辑与 body 类型无关,完全一致。

### params:通用冻结常量命名空间(唯一 kernel/eval 增强)

**动机**:Script 的 `source` 与 Flow 的 `SwitchNode.expression`/`TransformNode.expression` 是交给表达式引擎求值的**不透明文本**,其"参数"是文本内部的子串,JsonPointer 指不进去。若靠文本占位(`amount > #{x}`)则 skeleton 不再是合法 body、且是首版 token 之罪的翻版;若靠子串偏移定位则脆弱。**唯一干净的统一办法:把参数从"文本内部"提到"body 的一等结构字段"。**

**机制**:表达式按名引用一个 `params` 顶层命名空间(与现有 `metrics`/`payload`/`subject`/`now`/`flow` 平级、点号访问),求值期由 executor 并入 binding:

```
skeleton:  source = "metrics.balance > params.threshold"     // 引用 param 名,合法可求值
           params = { threshold: 100 }                        // 默认值就位
slot 绑定:  JsonPointerTarget("/script/params/threshold")      // 与 AST/Flow 同一种 target
实例化:     bind 替换 /script/params/threshold → 新值(冻进 body)
运行时:     executor 把 params map put 进引擎 binding
```

三不变量全保住:skeleton 恒为合法可跑 body;无 token、无扫描;寻址的是 params map 的 key(结构位置),非源码子串。命名空间隔离 → `params.threshold` 与 `metrics.threshold` 天然不撞,无扁平合并、无覆盖规则。

**存储**:`params` 随 body 序列化进同一个 `rule_version.body` / `rule_template.body_skeleton` JSON 列,**零新增列、零新增表**。冻进 snapshot,守 D6。

**AST 与 Script 的差异(不是异类)**:统一发生在**模板层**(都用 JsonPointer 把值冻进 body 的某个位置);运行时怎么消费冻好的 body 本就各 kind 不同(AST executor 从节点直接读值;script/flow 引擎从 binding 读、值靠 put 注入)——这是不同 executor 的定义,与模板机制正交。

**触点(6 处,全机械,照抄现有 metrics/payload/subject 定式)**:

1. `ScriptSource`(kernel/api/model,现 `record(source, lang)`)加 `params : Map<String,Object>`,compact 构造缺省空 map + `@JsonSetter(nulls = Nulls.AS_EMPTY)`(Jackson3 缺键安全)。
2. `FlowGraph`(kernel/api/model/flow)加 `params : Map<String,Object>`,同上缺省空 map。
3. `ScriptExecutor.execute`:`Map<String,Object> b = new HashMap<>(ScriptBindings.from(ctx)); b.put("params", script.params()); engine.evaluate(compiled, b);`(与 `FlowExecutor` 加 `flow`/`hitDecisions` 同构)。
4. `FlowExecutor.evalExpr`:`bindings.put("params", flowGraph.params());`。
5. `CelExpressionEngine`(强类型,必须声明命名空间):运行期 compiler 加 `.addVar("params", MapType.create(STRING, DYN))`;`typeCheck` 加同款声明(dyn,不做字段级检查,同 `subject`);`adaptBindings` 把 `params` 纳入数值规整数组。
6. 弱引擎(Aviator/JEXL/QLExpress/JsonLogic/Groovy):**零改动**(动态类型)。其 `extractDotPaths` 正则只匹配 `metrics|payload|subject`,`params.*` 不会被误抽成依赖——正确(params 是冻结常量,非 metric/payload)。

`ScriptBindings.from(ctx)` **不动**(只投影 ctx;params 来自 body/snapshot)。

### TemplateBinder SPI(kind 单点收口)

```java
public interface TemplateBinder {
    /** 是否处理该 body 变体。 */
    boolean supports(RuleBody body);
    /** 校验 bindings:每个 target 在 skeleton 可解析到已存在节点 + slot↔binding 1:1 双射 + slot key 无重复 + body 专属守卫;不符抛带错误码异常。 */
    void validate(RuleBody skeleton, List<SlotBinding> bindings, List<TemplateSlot> slots);
    /** 按 bindings 把 values 填入 skeleton 对应位置,返回新 body(不改入参)。 */
    RuleBody bind(RuleBody skeleton, List<SlotBinding> bindings, Map<String, Object> values);
}
```

- Spring 注入 `List<TemplateBinder>`,`RuleTemplateService` 按 `supports(body)` 选一个 dispatch;**无匹配 → 拒 `TEMPLATE_KIND_UNSUPPORTED`**(取代首版 5 处散落 `instanceof` throw)。
- **v1 唯一实现 `JsonPointerBinder`**:`supports = body instanceof AstBody || ScriptBody || FlowBody`(即当前全部)。`bind` 用 Jackson `JsonPointer` 对 body 的 `JsonNode` 寻址替换,3 种 body 逻辑一致。`validate` 里按 body 类型加**专属守卫**:
  - 通用:每个 target pointer 在 skeleton 解析到已存在节点(强制默认值就位)、slot↔binding 1:1、slot key 无重复。
  - ScriptBody:target 只允许 `/script/params/*`(拒 `/script/source`、`/script/lang`)。
  - FlowBody:target 拒指 `/referencedSnapshots`(那是发布期冻结的跨规则快照,由实例化管线重冻,模板不碰)。
- **加一种 kind**:若是 JSON 可寻址的 body,`JsonPointerBinder` 已覆盖、零改动;若是"非 JSON 结构、需另类寻址"的 body,才加新 binder + 新 `SlotTarget` permit——SPI 与 sealed 外壳为此留口。

### 校验(分两层,各就其位)

- **模板层(create/update,scene 无关)**:选 binder → `binder.validate(skeleton, bindings, slots)`(如上守卫)+ 复用 `PublishService.validateKindBodyConsistent(kind, body)`(kind↔body 一致,区分 AstBody 内 4 种 AST kind)。无匹配 binder → 拒 `TEMPLATE_KIND_UNSUPPORTED`。
- **实例化层(scene 相关)**:`validateSlotValues(slots, values)`——required 齐全 + 按 `DataType` **强转** + `SlotConstraint` 校验。强转表:
  - `LONG`/`DOUBLE` → 数值;`DECIMAL` → 保精度表示(字符串 / BigDecimal,避免 JSON number 精度丢失);`BOOLEAN` → 布尔;`STRING` → 串;`DATE`/`DATETIME` → ISO-8601 串(校验可解析);`LIST` → 数组。
  - `SlotConstraint.min/max` 仅对数值标量;`enumValues` 对标量或 LIST 逐元素成员校验。
- **注意(避坑)**:metric/payload 闭合校验是 **per-scene** 的,模板是 tenant 级、发布时无 scene,**做不了** skeleton 的全量 `resolveAndValidate`。该闭合冻结留到实例化经 `createDraft → resolveAndValidate` 完成。故不设"模板发布时 dry 全量校验",模板层只做上述 scene 无关的结构校验。

### 实例化流程

```java
RuleTemplate t = mapper.findPublishedByCode(tenantId, code);            // 必须 PUBLISHED
TemplateBinder binder = pick(t.bodySkeleton());                        // supports() 选择,无则拒
validateSlotValues(t.slots(), slotValues);                            // required/强转/constraint
RuleBody bound = binder.bind(t.bodySkeleton(), t.bindings(), slotValues);
RuleContent content = new RuleContent(ruleName, t.kind().tag(), bound, List.of(), List.of(), triggerEventTypes);
return publishService.createDraft(tenantId, sceneCode, ruleCode, content, actorId, t.id(), t.version());
```

产物是普通 DRAFT `RuleVersion`,走 `resolveAndValidate` 全量冻结校验(metric/payload 依赖、kind↔body 一致),发布/灰度/回滚/评估全复用现有管线。`rule_version.template_id`/`template_version` 记溯源(快照式,不联动)。

**DECISION_FLOW 的被引规则**:`RuleRefNode.ruleCode` 按 **`(tenant, ruleCode)` 租户级**解析(`findByTenantAndCode`,ruleCode tenant 内唯一,可跨 scene 引用),被引规则 ACTIVE 快照由实例化的 `resolveAndValidate` **按 tenant 重新冻结**——与手建 flow 规则同一条路。模板 skeleton **不携带、不传播** `referencedSnapshots`(避免过期跨规则快照,守 D6);其冻结不依赖实例化 scene。

## 数据模型迁移(改写 V1_42)

`V1_42` 未 apply、表不存在,直接改写为一次成型:

```sql
CREATE TABLE rule_template (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL,
    code          VARCHAR(128) NOT NULL,
    name          VARCHAR(256) NOT NULL,
    description   VARCHAR(1024),
    kind          VARCHAR(32)  NOT NULL,
    body_skeleton JSON         NOT NULL COMMENT '合法 body 骨架,默认值就位,无 token',
    slots         JSON         NOT NULL COMMENT 'TemplateSlot[] 参数 schema',
    bindings      JSON         NOT NULL COMMENT 'SlotBinding[],slot→body 位置显式绑定',
    version       INT          NOT NULL,
    status        VARCHAR(32)  NOT NULL,
    created_by    VARCHAR(64),
    created_at    DATETIME,
    updated_by    VARCHAR(64),
    updated_at    DATETIME,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_code (tenant_id, code)
) COLLATE = utf8mb4_unicode_ci;   -- 显式 collation,避免与既有表 join 报 1267

ALTER TABLE rule_version
    ADD COLUMN template_id      BIGINT NULL COMMENT '实例化来源模板 ID(手建规则为 null)',
    ADD COLUMN template_version INT    NULL COMMENT '实例化时模板版本号';
```

`rule_version.body` 已是 JSON 列,`ScriptSource.params`/`FlowGraph.params` 加字段不动表结构。

## 组件改动清单

**rule-kernel(唯一 kernel/eval 触点,params 命名空间)**
- `ScriptSource` 加 `params` 字段;`FlowGraph` 加 `params` 字段。
- `ScriptExecutor.execute`、`FlowExecutor.evalExpr` 并入 `params` binding。
- 各 `ScriptSource` 构造点(SDK / 测试)补空 params(record 缺省已处理,显式构造点跟随)。

**rule-expression-cel**
- `CelExpressionEngine` 运行期 compiler + `typeCheck` + `adaptBindings` 三处注册 `params` 命名空间。

**rule-config-svc**
- 新增 `api/dto/SlotBinding.java`、`SlotTarget.java`(sealed)+ `JsonPointerTarget.java`。
- 改 `TemplateSlot`(dataType 改 `DataType`、去 `defaultValue`)、`SlotConstraint`(接入 min/max/enumValues 校验)。
- 新增 SPI `internal/template/TemplateBinder.java` + 唯一实现 `internal/template/JsonPointerBinder.java`。
- 改 `RuleTemplate` 实体:加 typed `bindings`(`@TableField(typeHandler = Jackson3TypeHandler.class)`);`templateBody` 更名/语义改为 `bodySkeleton`。
- **重写 `RuleTemplateServiceImpl`**:删 `collectSlotKeys`/`replaceSlotsInMap`/`validateSlotsConsistent`/`setJsonPathValue`/`deepCloneAndReplace`/`deepClone`/`exportFromRule` 等 token 与反向导出逻辑;改注入 `List<TemplateBinder>` + `supports` dispatch。
- `RuleTemplateService` 接口:`create`/`update` 签名加 `bindings`;删 `exportFromRule`。

**rule-api**
- create/update 请求 DTO 加 `bindings`;**删** `ExportFromRuleRequest`(及 `SlotExtraction`)+ export 端点。端点 7 个:create/update/publish/disable/list/get/instantiate。
- DTO ↔ service 走 MapStruct(`web/admin/convert/`)或字段极少时手写。

**前端**
- `types/template.ts`:加 `SlotBinding`/`SlotTarget`/`JsonPointerTarget`;`RuleTemplate` 加 `bindings`、去 slot `defaultValue`。script/flow body 类型加 `params`。
- **模板编辑器(前端改动最大处)**:复用现有 rule-editor 组件渲染 skeleton(真实默认值),叠加"选中 body 位置 → 声明该位置绑到哪个 slot + 编辑 slot schema"层。编辑器产出 `(JsonPointer, slot schema)` 对。UX 细节留待实现,功能默认关不追求打磨。
- **删** 反向导出页;实例化表单不变(按 slots 动态渲染,默认值取 skeleton 位置值)。

**开关**:默认关闭不变(`RuleTemplateController` + `RuleTemplateServiceImpl` 带 `@ConditionalOnProperty(rule.template.enabled)`;前端 `FEATURES.templates`)。

## 错误码

- `TEMPLATE_KIND_UNSUPPORTED`:无 binder `supports` 该 body。
- `TEMPLATE_BINDING_UNRESOLVABLE`:target pointer 在 skeleton 解析不到已存在节点。
- `TEMPLATE_SLOT_BINDING_MISMATCH`:slot↔binding 非 1:1 双射 / slot key 重复。
- `TEMPLATE_TARGET_FORBIDDEN`:target 指向禁区(`/script/source`、`/script/lang`、`/referencedSnapshots`)。
- `TEMPLATE_SLOT_VALUE_INVALID`:实例化填值强转失败 / 违反 constraint / 缺必填。

## What We're NOT Doing

- **不做 B 活链接**(模板改动传播到实例)——撞 D6/D60,另立项目。
- **不做反向导出**(`exportFromRule`/`locate`/`ExtractPoint`)——正向 create/instantiate 是核心,反向导出无近期场景;真需要时 `locate` 是 `bind` 的逆、共用 `JsonPointerTarget`,纯增量加回。
- **不参数化脚本 source 内容本身**(拒 `/script/source`)——只参数化 `params` 里的值,脚本逻辑由模板固定,语义可校验。
- **不启用功能**——开关默认关闭,待真实业务场景。
- **kernel/eval 除 `params` 命名空间外零改动**——评估产物是普通 RuleVersion。

## 测试策略

- **JsonPointerBinder 单测**:JsonPointer 寻址各 body(AndNode 深层 / ScorecardRootNode band / DecisionTableNode cell / IfNode 树 / `/script/params/*` / `/flowGraph/nodes/*/ruleCode` / `/flowGraph/params/*`)的 bind + validate 往返;无效 pointer 拒;禁区 target 拒;slot↔binding 双射;**直接覆盖首版漏掉的 DECISION_TREE/DECISION_TABLE 深层**。
- **params 命名空间单测(kernel/eval)**:`ScriptExecutor`/`FlowExecutor` 把 params 并入 binding、`params.x` 可求值;`CelExpressionEngine` `params.x` compile + typeCheck 通过、数值规整正确;弱引擎 `params.*` 不被抽成依赖。
- **Service 单测**:create/update/publish/disable/instantiate + 审计事件(mock mapper/binder)。
- **实例化集成测试**:填值 → bind → createDraft → 产物与手建规则行为一致 + `rule_version.template_id/version` 回填;flow 模板实例化后被引规则快照按 `(tenant, ruleCode)` 正确冻结。
- **开关测试**:`enabled=false` 时 bean 不装配、端点 404。
- 提交前 `$MVN -pl <module> -am test` 全绿(跨模块带 `-am`);一轮结束 `$MVN clean test` 兜底(kernel 加字段后必须 clean 重编译全部 test)。

## 功能端到端(集成绿之后)

起真实服务(打包产物,非 reactor run 目标)→ 建 scene/metric/decision 依赖 → 建模板(create + publish)→ 实例化 → 查 `rule_version` 确认 body(含替换后的值 / params)、`template_id`/`template_version` 真落库 → 走评估入口验证产物行为 → 清理测试数据恢复干净基线。参考 `docs/examples/`。

## 文档善后(本轮一并修正首版过度宣称)

- `00-decisions.md` D74:改为"实验性预实现,默认关闭,binder SPI 重设计(2026-07-24):JsonPointer 统一寻址覆盖全 6 kind + params 冻结常量命名空间消除 Script/Flow 异类,待真实场景启用"。
- `reference-projects.md` Drools 行:从"已吸收"退回"待触发(实验性铺路)"。
- plan 定稿后落 `docs/superpowers/plans/`,删系统临时路径旧 plan。

## References

- 现 Service token 逻辑(待删):`rule-config-svc/.../internal/service/RuleTemplateServiceImpl.java`
- binder SPI 参照定式:`ExpressionEngine`(6 引擎自动收集)、`RuleVersionExecutor`(每 kind 一个)
- body 多态基座:`rule-kernel/.../api/model/RuleBody.java`(D76 sealed);`AstNode`(9 变体 sealed)
- 实例化下游:`PublishService.createDraft(...templateId, templateVersion)`(第 877 行,已存在)
- params 触点:`ScriptSource` / `ScriptBody` / `FlowGraph` / `ScriptExecutor` / `FlowExecutor` / `CelExpressionEngine` / `ScriptBindings`
- dataType 单一真相源:`rule-kernel/.../api/model/DataType.java`
- JsonPointer:Jackson `tools.jackson.core.JsonPointer`(对 body 的 JsonNode 寻址)
- 依赖抽取正则(确认 params 透明):`AviatorExpressionEngine.extractDotPaths`(`\b(metrics|payload|subject)\.`)
