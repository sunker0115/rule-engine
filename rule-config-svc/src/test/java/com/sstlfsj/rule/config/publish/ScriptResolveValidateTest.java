package com.sstlfsj.rule.config.publish;

import com.sstlfsj.rule.config.api.dto.PayloadFieldSpec;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.domain.MetricStatus;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.publish.PublishService;
import com.sstlfsj.rule.config.internal.repository.DecisionDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import com.sstlfsj.rule.expression.cel.CelExpressionEngine;
import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import com.sstlfsj.rule.kernel.api.model.PayloadDependency;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.ScriptSource;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 验证 resolveAndValidate 的 EXPRESSION_SCRIPT 分支（引擎无关层）：
 * ① 脚本引用 metrics.X/payload.Y 经 referencedVariables 冻成依赖；
 * ② conditionAst 为 null、script 原样透传；③ 无对应引擎/编译失败抛 IllegalArgumentException。
 * 用真实 {@link CelExpressionEngine} 注入 PublishService，mapper 用 mock。
 */
@ExtendWith(MockitoExtension.class)
class ScriptResolveValidateTest {

    @Mock RuleDefinitionMapper ruleDefinitionMapper;
    @Mock SceneMapper sceneMapper;
    @Mock RuleVersionMapper ruleVersionMapper;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock MetricDefinitionMapper metricDefinitionMapper;
    @Mock DecisionDefinitionMapper decisionDefinitionMapper;

    private PublishService publishService;
    private SceneDef scene;

    @BeforeEach
    void setUp() {
        List<ExpressionEngine> engines = List.of(new CelExpressionEngine());
        publishService = new PublishService(ruleDefinitionMapper, sceneMapper, ruleVersionMapper,
                eventPublisher, metricDefinitionMapper, decisionDefinitionMapper, engines);

        scene = new SceneDef();
        scene.setId(5L);
        scene.setCode("PAYMENT");
        scene.setEventTypes(List.of("payment.initiated"));
        // payload.amount 须在 payloadSchema 声明，否则冻 payload 依赖会拒
        scene.setPayloadSchema(List.of(
                new PayloadFieldSpec("amount", "NUMBER", true, null, null, null, null, null)));
    }

    @Test
    void scriptBranch_freezesMetricAndPayloadDeps_astNull_scriptPassedThrough() {
        // metrics.txn_cnt_1d 须有 ACTIVE 定义供冻结
        MetricDefinition md = new MetricDefinition();
        md.setMetricCode("txn_cnt_1d");
        md.setDataType("LONG");
        md.setVersion(2);
        md.setStatus(MetricStatus.ACTIVE);
        when(metricDefinitionMapper.findActiveByCodes(any(), any())).thenReturn(List.of(md));

        ScriptSource script = new ScriptSource(
                "metrics.txn_cnt_1d > 50 && payload.amount > 0 ? 'REVIEW' : 'PASS'", "CEL");

        PublishService.ResolvedDraft resolved = publishService.resolveAndValidate(
                1L, scene, RuleKind.EXPRESSION_SCRIPT,
                null, List.of(), List.of(), List.of(), script);

        // metric 依赖从 refVars(metrics.*) 冻入
        assertThat(resolved.metricDeps()).containsExactly(new MetricDependency("txn_cnt_1d", 2));
        // payload 依赖从 refVars(payload.*) 冻入
        assertThat(resolved.payloadDeps())
                .extracting(PayloadDependency::name)
                .containsExactly("amount");
        // 脚本规则不进 AST
        assertThat(resolved.resolvedAst()).isNull();
        // script 原样透传
        assertThat(resolved.scriptSource()).isEqualTo(script);
        assertThat(resolved.kind()).isEqualTo(RuleKind.EXPRESSION_SCRIPT);
    }

    @Test
    void scriptBranch_compileError_throwsIllegalArgument() {
        // 语法错（未闭合括号）→ ExpressionCompileException → IllegalArgumentException
        ScriptSource bad = new ScriptSource("metrics.txn_cnt_1d > ", "CEL");
        assertThatThrownBy(() -> publishService.resolveAndValidate(
                1L, scene, RuleKind.EXPRESSION_SCRIPT,
                null, List.of(), List.of(), List.of(), bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("脚本编译失败");
    }

    @Test
    void scriptBranch_typeMismatch_throwsIllegalArgument() {
        // STRING payload 字段参与数值比较 → typed 类型检查在发布期拒(运行期才报会太晚)
        scene.setPayloadSchema(List.of(
                new PayloadFieldSpec("country", "STRING", true, null, null, null, null, null)));
        ScriptSource bad = new ScriptSource("payload.country > 100 ? 'REVIEW' : 'PASS'", "CEL");
        assertThatThrownBy(() -> publishService.resolveAndValidate(
                1L, scene, RuleKind.EXPRESSION_SCRIPT,
                null, List.of(), List.of(), List.of(), bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("脚本类型检查失败");
    }

    @Test
    void scriptBranch_noEngineForLang_throwsIllegalArgument() {
        // lang=JS 无对应引擎 → 发布期拒
        ScriptSource js = new ScriptSource("payload.amount > 0", "JS");
        assertThatThrownBy(() -> publishService.resolveAndValidate(
                1L, scene, RuleKind.EXPRESSION_SCRIPT,
                null, List.of(), List.of(), List.of(), js))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无对应表达式引擎");
    }

    @Test
    void constructor_duplicateLangEngines_throwsIllegalState() {
        // 引擎路由 fail-fast 对齐 eval-svc：同 lang 重复声明应在装配期拒，而非静默覆盖
        ExpressionEngine dup = new CelExpressionEngine();
        List<ExpressionEngine> engines = List.of(new CelExpressionEngine(), dup);
        assertThatThrownBy(() -> new PublishService(ruleDefinitionMapper, sceneMapper, ruleVersionMapper,
                eventPublisher, metricDefinitionMapper, decisionDefinitionMapper, engines))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CEL");
    }
}
