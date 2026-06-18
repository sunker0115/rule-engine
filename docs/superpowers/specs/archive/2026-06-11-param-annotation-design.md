# SPI 注解参数 schema 类型安全化 — `@Param`

> 状态:**DEFERRED(暂缓)** · 日期 2026-06-11
>
> **暂缓原因**:`paramsSchema` 当前无消费方——`MetadataServiceImpl.getSceneMetadata` 仍把 `conditionTypes`/`actionTypes` 返回空列表("v1 未实现"),全仓无任何反射读取 `@ConditionType.paramsSchema()` 的代码。在"扫描 SPI 注解 → 喂前端元数据"这条管线建起来之前,`@Param` 是给空气造接口(YAGNI)。**待该元数据管线立项时,把 `@Param` + 扫描管线一并设计落地**(届时才有真实消费方)。本 spec 作为已成形的设计存档。

## 一、背景与动机

`@ConditionType` / `@ActionType` / `@MetricSourceType` 三个 SPI 注解用 `paramsSchema() default "{}"`(**JSON-Schema 字符串**)声明 handler 参数,供 `MetadataService` 解析成 `Map<String,Object>` 给前端渲染参数输入。手写 JSON-Schema 字符串不友好:无编译期校验、易 typo、可读性差。

约束:Java 注解只能放常量/枚举/嵌套注解/Class,**无法表达任意对象**,故"任意嵌套 JSON-Schema 完全类型安全"在注解里做不到。绝大多数 condition/action/metric 参数是**扁平的带类型字段**,正好可用嵌套注解表达。

## 二、设计

### 2.1 新增 `@Param` 嵌套注解 + `ParamType` 枚举(rule-kernel `api/annotation`)

```java
public @interface Param {
    String name();                       // 参数名(= JSON-Schema properties key)
    ParamType type();                    // 参数类型
    boolean required() default false;    // 是否必填
    String description() default "";     // 展示说明(前端 label/tooltip)
    String defaultValue() default "";    // 默认值(字符串形式,前端预填)
}

public enum ParamType {
    STRING, NUMBER, INTEGER, BOOLEAN, ARRAY, OBJECT;  // 映射 JSON-Schema type 小写
}
```

### 2.2 三个 SPI 注解新增 `params()`,保留 `paramsSchema()` 逃生口

```java
public @interface ConditionType {
    String value();
    String displayName() default "";
    Param[] params() default {};         // 结构化参数(推荐)
    String paramsSchema() default "{}";  // 复杂/嵌套 schema 的逃生口(与 params 二选一)
}
```
`@ActionType` / `@MetricSourceType` 同样加 `Param[] params() default {}`,保留各自 `paramsSchema()`。

### 2.3 元数据装配:`@Param[]` → JSON-Schema Map(前端契约不变)

`MetadataService` 实现处(扫描 `@ConditionType` 等 Bean 构建 `ConditionTypeMeta` 的地方):
- `params()` 非空 → 由 `@Param[]` 构建 `paramsSchema` Map:`{"type":"object","required":[必填名...],"properties":{name:{"type":小写type, "description":..., "default":...}}}`。
- `params()` 为空 → 回落解析 `paramsSchema()` 字符串(现有逻辑)。
- 二者都给时以 `params()` 为准(并在扫描期 log warn 提示重复声明)。
- **前端拿到的 `paramsSchema` Map 结构不变**,纯后端表达方式升级。

### 2.4 启动期校验(顺带补强)

扫描期对 `paramsSchema()` 字符串做 JSON 解析,非法 JSON **fail-fast**(`IllegalStateException`,带注解类名),不再等运行时才暴露。

## 三、迁移

- 现有 handler 的 `@ConditionType` / `@ActionType`(如内置 `AMOUNT_GT`、`SEND_ALert` 等)若用了 `paramsSchema` 字符串且为扁平参数,改用 `@Param[]`;复杂的保留字符串。
- 04-extension §二/§三/§四的注解声明示例与 §5.2 元数据结构示例同步更新(说明两种写法、推荐 `@Param`)。

## 四、非目标(YAGNI)

- 不支持任意深度嵌套 schema 的结构化表达(保留 `paramsSchema` 字符串逃生口承接)。
- 不改前端契约(`paramsSchema` 仍以 JSON-Schema Map 下发)。
- 不动 `@RuleDef` / `@DecisionBinding`(它们无 paramsSchema)。

## 五、影响面

`rule-kernel`(新增 `Param`/`ParamType`,改 3 注解)、`MetadataService` 实现(`@Param[]`→Map + JSON 校验)、现有 handler 注解(迁移扁平参数)、`docs/04-extension.md`(§二/§三/§四/§5.2)、相关测试。改完派 `rule-engine-reviewer` 审代码↔文档对齐。

## 六、验收

- `@Param[]` 声明的 handler,经 `/admin/v1/.../metadata`(或对应元数据接口)返回的 `paramsSchema` Map 与等价手写 JSON-Schema 一致。
- 非法 `paramsSchema` 字符串启动期 fail-fast。
- 全量 `clean test` 绿。
