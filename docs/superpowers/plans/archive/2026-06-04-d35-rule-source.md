# D35 RuleSource 抽象 + 四种规则来源模式实现计划

> **Goal：** 引入 `RuleSource` SPI，将规则装载方式与 `RuleEngineClient` 解耦。本次实装 `DslRuleSource`（取代 D34 的 `localSnapshot()`）和 `FileRuleSource`（JSON 文件加载），`PollingRuleSource` 封装已有 `SnapshotPoller` 逻辑，`AnnotationRuleSource` 留空占位。
>
> **决策依据：** D35（`00-decisions.md`）

---

## 影响范围

| 模块 | 文件 | 变更类型 |
|------|------|---------|
| `rule-sdk` | `RuleSource.java` | 新建 SPI 接口 |
| `rule-sdk` | `DslRuleSource.java` | 新建（取代 `localSnapshot()` 实现） |
| `rule-sdk` | `FileRuleSource.java` | 新建（JSON 文件加载） |
| `rule-sdk` | `PollingRuleSource.java` | 新建（封装 `SnapshotPoller`） |
| `rule-sdk` | `RuleEngineClient.java` | Builder 新增 `ruleSource()` / `ruleFile()` 入口，内部统一走 `RuleSource.loadInto()` |
| `rule-sdk` | `RuleEngineClientTest.java` | 补 `FileRuleSource` + `DslRuleSource` 测试 |

**不改的**：`EvalEngine`、`SceneRuleIndex`、`SnapshotPoller`（被 `PollingRuleSource` 委托而不是替换）、服务端任何模块。

**向后兼容**：`localSnapshot()` 方法保留，内部委托给 `DslRuleSource`，不破坏 D34 已有调用方。

---

## Maven 环境

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
```

---

## 文件格式约定

`FileRuleSource` 读取的 JSON 格式与服务端 `GET /api/v1/sdk/snapshots` 响应体 `data` 数组格式一致，直接由 `AstJsonCodec` 反序列化：

```json
[
  {
    "ruleVersionId": 1,
    "sceneCode": "fraud",
    "tenantId": "t1",
    "kind": "AST_BOOLEAN",
    "triggerEventTypes": ["TRANSACTION"],
    "decisionBindings": [{"decisionCode": "BLOCK", "priority": 100}],
    "preGates": [],
    "conditionAst": {
      "type": "AND",
      "children": [
        { "type": "CONDITION", "conditionType": "GT", "metricCode": "amount",
          "params": {"threshold": 1000}, "weight": 0.0 }
      ]
    }
  }
]
```

---

## Task 1：定义 `RuleSource` SPI 接口

新建 `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/source/RuleSource.java`：

```java
package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;

/** 规则来源 SPI：将规则快照装载到评估索引。 */
public interface RuleSource {
    /**
     * 将本来源持有的规则快照写入索引。
     * 实现须幂等：同一 ruleVersionId 重复写入不产生重复条目。
     *
     * @param index 目标评估索引
     */
    void loadInto(SceneRuleIndex index);
}
```

---

## Task 2：实现 `DslRuleSource`

封装 D34 `localSnapshot()` 写入逻辑，新建
`rule-sdk/src/main/java/com/sstlfsj/rule/sdk/source/DslRuleSource.java`：

```java
package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;

import java.util.ArrayList;
import java.util.List;

/** 代码 DSL 模式：直接持有 RuleVersionSnapshot 列表，零网络、零 IO。 */
public class DslRuleSource implements RuleSource {

    private final List<RuleVersionSnapshot> snapshots;

    public DslRuleSource(List<RuleVersionSnapshot> snapshots) {
        this.snapshots = List.copyOf(snapshots);
    }

    @Override
    public void loadInto(SceneRuleIndex index) {
        for (RuleVersionSnapshot snap : snapshots) {
            List<String> eventTypes = snap.triggerEventTypes().isEmpty()
                    ? List.of("*") : snap.triggerEventTypes();
            for (String et : eventTypes) {
                List<RuleVersionSnapshot> existing = new ArrayList<>(
                        index.match(snap.tenantId(), snap.sceneCode(), et));
                if (existing.stream().noneMatch(s -> s.ruleVersionId().equals(snap.ruleVersionId()))) {
                    existing.add(snap);
                }
                index.update(snap.tenantId(), snap.sceneCode(), et, existing);
            }
        }
    }
}
```

---

## Task 3：实现 `FileRuleSource`

新建 `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/source/FileRuleSource.java`：

```java
package com.sstlfsj.rule.sdk.source;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/** JSON 文件模式：从 classpath 或文件系统加载规则快照，适合离线/测试场景。 */
public class FileRuleSource implements RuleSource {

    private final InputStream input;

    private FileRuleSource(InputStream input) {
        this.input = input;
    }

    /**
     * 从 classpath 加载规则 JSON 文件。
     *
     * @param path classpath 相对路径，如 "rules/fraud.json"
     */
    public static FileRuleSource classpath(String path) {
        InputStream in = FileRuleSource.class.getClassLoader().getResourceAsStream(path);
        if (in == null) throw new IllegalArgumentException("classpath 资源不存在：" + path);
        return new FileRuleSource(in);
    }

    @Override
    public void loadInto(SceneRuleIndex index) {
        ObjectMapper mapper = new ObjectMapper();
        List<RuleVersionSnapshot> snapshots;
        try {
            snapshots = mapper.readValue(input,
                    new TypeReference<List<RuleVersionSnapshot>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException("规则文件解析失败", e);
        }
        new DslRuleSource(snapshots).loadInto(index);
    }
}
```

> **注意**：`FileRuleSource` 依赖 `jackson-databind` 对 `RuleVersionSnapshot` record 的反序列化。需确认 `rule-sdk` pom 中已有该依赖（当前 `SnapshotPoller` 也在用 jackson，应已存在）。`conditionAst` 字段需要 `AstJsonCodec` 的自定义反序列化模块注册，否则 `@JsonTypeInfo` 分派会失败——Task 3 完成后跑测试即可验证。

---

## Task 4：实现 `PollingRuleSource`（封装 `SnapshotPoller`）

新建 `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/source/PollingRuleSource.java`：

```java
package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import com.sstlfsj.rule.sdk.FetchMode;
import com.sstlfsj.rule.sdk.SnapshotPoller;

import java.time.Duration;
import java.util.List;

/**
 * HTTP 轮询模式：封装 SnapshotPoller，定期从服务端拉取最新快照写入索引。
 * loadInto() 启动后台轮询线程；调用方负责在适当时机调用 stop()。
 */
public class PollingRuleSource implements RuleSource {

    private final SnapshotPoller poller;

    public PollingRuleSource(String serverUrl, String tenantId,
                             FetchMode fetchMode, List<String> scenes,
                             Duration pollInterval, SceneRuleIndex index) {
        this.poller = new SnapshotPoller(serverUrl, tenantId, fetchMode,
                scenes, pollInterval, index);
    }

    @Override
    public void loadInto(SceneRuleIndex index) {
        // index 已在构造时传入 poller，此处启动轮询
        poller.start();
    }

    /** 停止后台轮询线程，供 RuleEngineClient.close() 调用。 */
    public void stop() {
        poller.stop();
    }
}
```

---

## Task 5：改造 `RuleEngineClient` 统一走 `RuleSource`

### 变更说明

1. Builder 新增 `ruleSource(RuleSource)` 和 `ruleFile(String path)` 入口
2. 原 `localSnapshot(RuleVersionSnapshot)` 保留，内部追加到 `DslRuleSource` 的 snapshot 列表
3. 原 HTTP 模式（`serverUrl`）内部构造 `PollingRuleSource`
4. 构造器统一调用所有 `RuleSource.loadInto(index)`，可停止的 source 存入列表，`close()` 时逐个 stop

### 关键代码片段

```java
// Builder 新增字段
private final List<RuleSource> ruleSources = new ArrayList<>();

// 新增入口
public Builder ruleSource(RuleSource v) { ruleSources.add(v); return this; }

public Builder ruleFile(String classpathPath) {
    ruleSources.add(FileRuleSource.classpath(classpathPath)); return this;
}

// localSnapshot 保持向后兼容（内部委托）
public Builder localSnapshot(RuleVersionSnapshot v) {
    localSnapshots.add(v); return this;
}
```

构造器中：

```java
// 将 localSnapshots 合并成一个 DslRuleSource 加入列表
List<RuleSource> allSources = new ArrayList<>(b.ruleSources);
if (!b.localSnapshots.isEmpty()) {
    allSources.add(new DslRuleSource(b.localSnapshots));
}
if (hasServer) {
    allSources.add(new PollingRuleSource(b.serverUrl, b.tenantId,
            b.fetchMode, b.scenes, b.pollInterval, index));
}

// 统一 loadInto
for (RuleSource source : allSources) {
    source.loadInto(index);
}

// 记录可停止的 source
this.stoppableSources = allSources.stream()
        .filter(s -> s instanceof PollingRuleSource)
        .map(s -> (PollingRuleSource) s)
        .toList();
```

`close()` 改为：

```java
@Override
public void close() {
    stoppableSources.forEach(PollingRuleSource::stop);
}
```

---

## Task 6：补测试

在 `RuleEngineClientTest.java` 补充：

1. `ruleFile_loadsFromClasspath_evaluatesHit` — 放一个测试 JSON 到 `src/test/resources/rules/test-rule.json`，验证 `ruleFile()` 能加载并正确求值
2. `ruleSource_dslRuleSource_evaluatesHit` — 直接用 `DslRuleSource` 传入 `ruleSource()`

**测试 JSON 内容** (`src/test/resources/rules/test-rule.json`)：

```json
[
  {
    "ruleVersionId": 100,
    "sceneCode": "test",
    "tenantId": "t1",
    "kind": "AST_BOOLEAN",
    "triggerEventTypes": ["TEST_EVENT"],
    "decisionBindings": [{"decisionCode": "PASS", "priority": 10}],
    "preGates": [],
    "conditionAst": {
      "type": "AND",
      "children": [],
      "displayLabel": null,
      "weight": null
    }
  }
]
```

---

## Task 7：全量验证

```bash
$MVN -pl rule-sdk -am test
$MVN test
```

期望：全模块 BUILD SUCCESS

---

## Task 8：Commit

```bash
git add rule-sdk/src/main/java/com/sstlfsj/rule/sdk/source/ \
        rule-sdk/src/main/java/com/sstlfsj/rule/sdk/RuleEngineClient.java \
        rule-sdk/src/test/java/com/sstlfsj/rule/sdk/RuleEngineClientTest.java \
        rule-sdk/src/test/resources/rules/test-rule.json
git commit -m "feat(sdk): D35 RuleSource SPI + DslRuleSource + FileRuleSource + PollingRuleSource"
```
