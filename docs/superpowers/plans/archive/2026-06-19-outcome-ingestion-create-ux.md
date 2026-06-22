# OUTCOME_INGESTION 创建表单 UX 改进 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use `- [ ]`.

**Goal:** 把「创建回灌任务」Modal 从"手写 SQL"改成"结构化填表（数据源 Select + 表名/过滤/行数）→ 自动拼 SQL + 折叠预览"，同时暴露已注册数据源列表 API。

**Architecture:** 后端新增 `GET /admin/v1/datasources` 读 `MetricDataSourceRegistry.names()`；前端 Modal 重构：数据源变 Select（拉该接口），隐藏 SQL 细节，只暴露表名/附加过滤/行数，自动拼固定格式 SQL，折叠「预览 SQL」给高级用户验证；提交时把拼好的 SQL 送到现有 create API。

**Tech Stack:** Java 25 / Spring Boot 4 / antd5 TypeScript。无 DB 迁移，无新依赖。

**环境:** `$MVN`(先 `mvn-env` skill 设置)；跨模块带 `-am`。

---

### Task 1: 后端 `GET /admin/v1/datasources`

**Files:**
- Create: `rule-api/.../web/admin/DatasourceController.java`
- Create: `rule-api/src/test/.../web/admin/DatasourceControllerTest.java`

`MetricDataSourceRegistry` 已有 `names()` 返回 `Set<String>`，直接用即可。注意它在 eval-svc internal——rule-api 已依赖 rule-eval-svc，但 `MetricDataSourceRegistry` 是 `internal` 包，不能直接注入。

**解耦方式**：在 eval-svc api 新增一个极简 service 接口暴露数据源名列表。

**Files (revised):**
- Create: `rule-eval-svc/.../api/service/DatasourceNameService.java`
- Create: `rule-eval-svc/.../internal/metric/sql/DatasourceNameServiceImpl.java`
- Create: `rule-api/.../web/admin/DatasourceController.java`
- Test: `rule-api/.../web/admin/DatasourceControllerTest.java`

- [ ] **Step 1: eval-svc api 接口**

```java
// rule-eval-svc/.../api/service/DatasourceNameService.java
package com.sstlfsj.rule.eval.api.service;

import java.util.Set;

/** 已注册数据源名列表（供 OUTCOME_INGESTION 创建表单选择）。 */
public interface DatasourceNameService {
    /** @return MetricDataSourceRegistry 中已注册的全部逻辑数据源名。 */
    Set<String> registeredNames();
}
```

- [ ] **Step 2: eval-svc internal 实现**

```java
// rule-eval-svc/.../internal/metric/sql/DatasourceNameServiceImpl.java
package com.sstlfsj.rule.eval.internal.metric.sql;

import com.sstlfsj.rule.eval.api.service.DatasourceNameService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Set;

/** DatasourceNameService 实现：委托 MetricDataSourceRegistry.names()。 */
@Service
@RequiredArgsConstructor
public class DatasourceNameServiceImpl implements DatasourceNameService {

    private final MetricDataSourceRegistry registry;

    @Override
    public Set<String> registeredNames() {
        return registry.names();
    }
}
```

- [ ] **Step 3: rule-api Controller**

```java
// rule-api/.../web/admin/DatasourceController.java
package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.eval.api.service.DatasourceNameService;
import com.sstlfsj.rule.web.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/** 已注册数据源名列表接口（供 OUTCOME_INGESTION 创建表单数据源 Select）。 */
@RestController
@RequestMapping("/admin/v1/datasources")
@RequiredArgsConstructor
public class DatasourceController {

    private final DatasourceNameService datasourceNameService;

    /**
     * GET /admin/v1/datasources — 返回 MetricDataSourceRegistry 中已注册的数据源名列表。
     *
     * @return 数据源名列表（已排序，便于前端展示）
     */
    @GetMapping
    public ApiResponse<List<String>> list() {
        Set<String> names = datasourceNameService.registeredNames();
        return ApiResponse.ok(names.stream().sorted().toList());
    }
}
```

- [ ] **Step 4: Controller 测试**

```java
// rule-api/src/test/.../web/admin/DatasourceControllerTest.java
package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.eval.api.service.DatasourceNameService;
import com.sstlfsj.rule.web.common.ApiResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatasourceControllerTest {

    private final DatasourceNameService svc = mock(DatasourceNameService.class);
    private final DatasourceController controller = new DatasourceController(svc);

    @Test
    void list_returnsSortedNames() {
        when(svc.registeredNames()).thenReturn(Set.of("biz", "analytics", "fraud"));
        ApiResponse<List<String>> resp = controller.list();
        assertTrue(resp.success());
        assertEquals(List.of("analytics", "biz", "fraud"), resp.data());
    }

    @Test
    void list_emptyRegistry_returnsEmptyList() {
        when(svc.registeredNames()).thenReturn(Set.of());
        ApiResponse<List<String>> resp = controller.list();
        assertTrue(resp.success());
        assertTrue(resp.data().isEmpty());
    }
}
```

- [ ] **Step 5: 跑测试**

```
$MVN -pl rule-eval-svc,rule-api -am test \
  -Dtest='DatasourceControllerTest' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  2>&1 | grep -E "Tests run|BUILD|ERROR" | tail
```
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/api/service/DatasourceNameService.java \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/metric/sql/DatasourceNameServiceImpl.java \
        rule-api/src/main/java/com/sstlfsj/rule/web/admin/DatasourceController.java \
        rule-api/src/test/java/com/sstlfsj/rule/web/admin/DatasourceControllerTest.java
git commit -m "feat(api): GET /admin/v1/datasources——已注册数据源名列表(供 OUTCOME_INGESTION 创建表单)"
```

---

### Task 2: 前端 Modal 重构（结构化填表 → 自动拼 SQL + 折叠预览）

**Files:**
- Modify: `frontend/src/constants/api-endpoints.ts`
- Modify: `frontend/src/api/scheduledTask.ts`（或新增 `src/api/datasource.ts`）
- Modify: `frontend/src/pages/scheduled-task-list/index.tsx`

- [ ] **Step 1: 读现有文件**

先读 `frontend/src/pages/scheduled-task-list/index.tsx` 和 `frontend/src/api/scheduledTask.ts` 了解当前 Modal 结构和 api 模式。

- [ ] **Step 2: 加 DATASOURCE_LIST endpoint**

在 `api-endpoints.ts` 的 SCHEDULED_TASK_* 区域旁加：
```typescript
DATASOURCE_LIST: `${ADMIN}/datasources`,
```

- [ ] **Step 3: 加 fetchDatasources api**

在 `frontend/src/api/scheduledTask.ts`（或新建 `src/api/datasource.ts`）加：
```typescript
export async function fetchDatasources(): Promise<string[]> {
  const res = await apiClient.get<ApiResponse<string[]>>(ENDPOINTS.DATASOURCE_LIST);
  return res.data.data ?? [];
}
```

- [ ] **Step 4: 重构 Modal 表单**

在 `scheduled-task-list/index.tsx` 中：

**新增 state：**
```typescript
const [datasources, setDatasources] = useState<string[]>([]);
```

**Modal `onOpenChange` 时加载数据源列表（Modal open 时拉一次）：**
```typescript
// 打开 Modal 时调用
const handleOpenCreate = async () => {
  createForm.resetFields();
  setCreateOpen(true);
  const names = await fetchDatasources().catch(() => []);
  setDatasources(names);
};
// 把现有 onClick={() => setCreateOpen(true)} 改为 onClick={handleOpenCreate}
```

**把原来的 6 个字段（code/name/cron/datasource/sql）改为 7 个字段（code/name/cron/datasource/tableName/extraFilter/limitRows）：**

```tsx
{/* 数据源: Select */}
<Form.Item label="数据源" name="datasource" rules={[{ required: true, message: '请选择数据源' }]}
           extra="MetricDataSourceRegistry 中已注册的数据源">
  <Select
    placeholder="请选择数据源"
    options={datasources.map(n => ({ value: n, label: n }))}
    loading={datasources.length === 0 && createOpen}
    showSearch
  />
</Form.Item>

{/* 标签表名 */}
<Form.Item label="标签表名" name="tableName" rules={[{ required: true, message: '请输入表名' }]}
           extra="存放标签数据的表，须含 event_id / outcome_label / outcome_value / labeled_at 四列">
  <Input placeholder="biz_fraud_label" />
</Form.Item>

{/* 附加过滤条件(可选) */}
<Form.Item label="附加过滤条件" name="extraFilter"
           extra="可选。会追加到 WHERE 子句，如 status = 'CONFIRMED'。不填则只按租户和水位过滤">
  <Input placeholder="status = 'CONFIRMED'" />
</Form.Item>

{/* 每批行数上限 */}
<Form.Item label="每批行数上限" name="limitRows" initialValue={1000}
           extra="每次调度最多拉取的行数（按 labeled_at 升序）">
  <Input type="number" min={1} max={10000} />
</Form.Item>
```

**`onFinish` 改为：用字段自动拼 SQL，再调 create API：**

```typescript
onFinish={async (values: {
  code: string; name: string; cron: string;
  datasource: string; tableName: string;
  extraFilter?: string; limitRows?: number;
}) => {
  if (!currentId) { message.error('请先选择租户'); return; }
  const limit = values.limitRows ?? 1000;
  const extraWhere = values.extraFilter?.trim()
    ? `\n  AND (${values.extraFilter.trim()})`
    : '';
  const sql =
    `SELECT event_id, outcome_label, outcome_value, labeled_at\n` +
    `FROM ${values.tableName}\n` +
    `WHERE tenant_id = :tenantId\n` +
    `  AND (:watermark IS NULL OR labeled_at > :watermark)${extraWhere}\n` +
    `ORDER BY labeled_at ASC LIMIT ${limit}`;
  try {
    await createIngestionTask({
      tenantId: currentId,
      code: values.code,
      name: values.name,
      cron: values.cron,
      datasource: values.datasource,
      sql,
    });
    message.success('任务创建成功');
    setCreateOpen(false);
    load();
  } catch (err: unknown) {
    const msg = (err as { response?: { data?: { message?: string } } })
      ?.response?.data?.message;
    message.error(msg ?? '创建失败');
  }
}}
```

**在表单底部、提交按钮上方加「预览 SQL」折叠块：**

```tsx
{/* 预览 SQL（折叠，供高级用户验证） */}
<Form.Item noStyle shouldUpdate>
  {({ getFieldsValue }) => {
    const { tableName, extraFilter, limitRows } = getFieldsValue();
    if (!tableName) return null;
    const limit = limitRows ?? 1000;
    const extraWhere = extraFilter?.trim()
      ? `\n  AND (${extraFilter.trim()})`
      : '';
    const preview =
      `SELECT event_id, outcome_label, outcome_value, labeled_at\n` +
      `FROM ${tableName}\n` +
      `WHERE tenant_id = :tenantId\n` +
      `  AND (:watermark IS NULL OR labeled_at > :watermark)${extraWhere}\n` +
      `ORDER BY labeled_at ASC LIMIT ${limit}`;
    return (
      <Form.Item label="预览 SQL">
        <pre style={{ background: '#f5f5f5', padding: 8, borderRadius: 4,
                      fontSize: 12, margin: 0, whiteSpace: 'pre-wrap',
                      wordBreak: 'break-all' }}>
          {preview}
        </pre>
      </Form.Item>
    );
  }}
</Form.Item>
```

- [ ] **Step 5: tsc build 验证**

```bash
cd /Users/sunke/dev/ai-project/rule-engine/frontend
npm run build 2>&1 | grep -E "error TS|✓ built" | head
```
Expected: `✓ built`（无 TS 错误）

- [ ] **Step 6: Commit**

```bash
git add frontend/src/constants/api-endpoints.ts \
        frontend/src/api/scheduledTask.ts \
        frontend/src/pages/scheduled-task-list/index.tsx
git commit -m "feat(frontend): 创建回灌任务 Modal 改为结构化填表——数据源 Select + 表名/过滤/行数 → 自动拼 SQL + 折叠预览"
```

---

### Task 3: 全量验证

- [ ] **Step 1: 全量测试**

```
$MVN -pl rule-eval-svc,rule-api -am test -Dsurefire.failIfNoSpecifiedTests=false \
  2>&1 | grep -E "Tests run|BUILD|ERROR" | tail -5
```
Expected: BUILD SUCCESS

- [ ] **Step 2: tsc build 确认**

```bash
cd /Users/sunke/dev/ai-project/rule-engine/frontend
npm run build 2>&1 | grep -E "error TS|✓ built" | head
```

- [ ] **Step 3: Commit（如无额外改动则空提交）**

```bash
git commit --allow-empty -m "chore: 数据源 Select + SQL 预览 UX 改进全量验证"
```

---

## Self-Review

**Spec 覆盖:**
- 数据源 Select → `DatasourceController` + `DatasourceNameService` + `fetchDatasources` + Select ✅
- 结构化字段 → code/name/cron/datasource/tableName/extraFilter/limitRows ✅
- 自动拼 SQL → onFinish 内组装 ✅
- 折叠预览 → `shouldUpdate` + `<pre>` ✅
- 提交时送现有 create API(`createIngestionTask`)，interface 不变 ✅

**Placeholder 扫描:** 无。所有步骤有完整代码。

**类型一致性:** `fetchDatasources(): Promise<string[]>` 与 `datasources: string[]` state、Select options 一致。`onFinish` 的 values 类型含全部 7 字段，sql 由 ts string 拼接正确。
