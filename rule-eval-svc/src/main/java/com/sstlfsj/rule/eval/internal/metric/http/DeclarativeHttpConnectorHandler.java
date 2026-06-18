package com.sstlfsj.rule.eval.internal.metric.http;

import com.sstlfsj.rule.config.api.connector.AuthScheme;
import com.sstlfsj.rule.config.api.connector.BearerAuth;
import com.sstlfsj.rule.config.api.connector.CompareOp;
import com.sstlfsj.rule.config.api.connector.ConnectorDescriptor;
import com.sstlfsj.rule.config.api.connector.ErrorMatch;
import com.sstlfsj.rule.config.api.connector.ErrorRule;
import com.sstlfsj.rule.config.api.connector.HttpMethod;
import com.sstlfsj.rule.config.api.connector.HttpRequestTemplate;
import com.sstlfsj.rule.config.api.connector.OAuth2ClientCredentialsAuth;
import com.sstlfsj.rule.config.api.connector.Predicate;
import com.sstlfsj.rule.config.api.connector.ResiliencePolicy;
import com.sstlfsj.rule.config.api.connector.RetryTrigger;
import com.sstlfsj.rule.config.api.connector.StaticHeaderAuth;
import com.sstlfsj.rule.config.api.connector.TemplateParam;
import com.sstlfsj.rule.eval.internal.metric.DataTypeCoercion;
import com.sstlfsj.rule.eval.internal.metric.fetch.MetricFetchErrorMapper;
import com.sstlfsj.rule.eval.internal.metric.fetch.ResiliencePolicyExecutor;
import com.sstlfsj.rule.eval.internal.metric.fetch.RetryableUpstreamStatusException;
import com.sstlfsj.rule.eval.internal.metric.fetch.VariableRenderer;
import com.sstlfsj.rule.kernel.api.annotation.MetricSourceType;
import com.sstlfsj.rule.kernel.api.model.MetricFetchError;
import com.sstlfsj.rule.kernel.api.model.MetricQuery;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.SourceType;
import com.sstlfsj.rule.kernel.api.model.ValueSource;
import com.sstlfsj.rule.kernel.api.spi.metric.FetchTraceCollector;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricSourceHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * EXTERNAL_HTTP 声明式连接器取数 handler：从 {@code params.connector} 解析连接器描述符，
 * 经 {@link VariableRenderer} 渲染请求模板、按 {@link AuthScheme} 鉴权、{@link ResiliencePolicyExecutor}
 * 发请求，再按 {@link com.sstlfsj.rule.config.api.connector.ResponseMapping} 判定成功并取值。
 * <p>所有失败均 catch 后归一为 {@link MetricValue#error} 细码，绝不抛到引擎。</p>
 */
@Component
@MetricSourceType(SourceType.EXTERNAL_HTTP)
public class DeclarativeHttpConnectorHandler implements MetricSourceHandler {

    private static final Logger log = LoggerFactory.getLogger(DeclarativeHttpConnectorHandler.class);

    private final HttpEndpointRegistry endpointRegistry;
    private final ConnectorDefinitionResolver connectorResolver;
    private final CredentialStore credentialStore;
    private final OAuth2TokenManager oauth2TokenManager;
    private final ObjectMapper objectMapper;
    private final VariableRenderer renderer = new VariableRenderer();
    private final MetricFetchErrorMapper errorMapper = new MetricFetchErrorMapper();
    private final ResiliencePolicyExecutor resilienceExecutor = new ResiliencePolicyExecutor();
    // 共享 HttpClient（线程安全）：通过构造器 @Nullable 注入——有 Bean 则用注入的，无则默认创建，测试可替换。
    private final HttpClient sharedHttpClient;

    /** Spring 注入主构造器：使用默认共享 HttpClient（connectTimeout 5s，read timeout 走 HttpRequest.timeout()）。 */
    @Autowired
    public DeclarativeHttpConnectorHandler(HttpEndpointRegistry endpointRegistry,
                                           ConnectorDefinitionResolver connectorResolver,
                                           CredentialStore credentialStore,
                                           OAuth2TokenManager oauth2TokenManager,
                                           ObjectMapper objectMapper) {
        this(endpointRegistry, connectorResolver, credentialStore, oauth2TokenManager, objectMapper, null);
    }

    /**
     * 允许注入自定义 HttpClient 的构造器（包级可见，供 Spring @Bean 手动装配或测试使用）。
     * httpClient 为 null 时自动创建默认共享实例。
     */
    DeclarativeHttpConnectorHandler(HttpEndpointRegistry endpointRegistry,
                                    ConnectorDefinitionResolver connectorResolver,
                                    CredentialStore credentialStore,
                                    OAuth2TokenManager oauth2TokenManager,
                                    ObjectMapper objectMapper,
                                    HttpClient httpClient) {
        this.endpointRegistry = endpointRegistry;
        this.connectorResolver = connectorResolver;
        this.credentialStore = credentialStore;
        this.oauth2TokenManager = oauth2TokenManager;
        this.objectMapper = objectMapper;
        this.sharedHttpClient = httpClient != null ? httpClient
                : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public MetricValue fetch(MetricQuery query) {
        return fetch(query, FetchTraceCollector.noop());
    }

    @Override
    public MetricValue fetch(MetricQuery query, FetchTraceCollector collector) {
        Object connectorName = query.params().get("connector");
        if (connectorName == null) return error(collector, MetricFetchError.NOT_FOUND);

        ConnectorDescriptor descriptor =
                connectorResolver.resolve(tenantId(query.tenantId()), connectorName.toString());
        if (descriptor == null) return error(collector, MetricFetchError.NOT_FOUND);

        HttpEndpointRegistry.Endpoint endpoint = endpointRegistry.get(descriptor.endpointRef());
        if (endpoint == null) return error(collector, MetricFetchError.NOT_FOUND);

        VariableRenderer.Context ctx = context(query);
        HttpRequest request;
        try {
            request = buildRequest(descriptor, endpoint, ctx);
        } catch (Exception e) {
            // 鉴权取值 / token 交换失败（CredentialMissingException 等）→ 归一为 UNAUTHORIZED。
            return error(collector, MetricFetchError.UNAUTHORIZED);
        }
        collector.renderedRequest(request.method() + " " + request.uri());

        ResiliencePolicy policy = descriptor.resilience();
        HttpResponse<String> response;
        try {
            HttpClient client = sharedHttpClient;
            response = resilienceExecutor.execute(policy, () -> {
                HttpResponse<String> r = client.send(request, HttpResponse.BodyHandlers.ofString());
                // 5xx 是正常响应不抛异常；retryOn 含 UPSTREAM_5XX 时主动抛 RetryableUpstreamStatusException 触发重试
                if (isServerError(r.statusCode()) && retryOnUpstream5xx(policy)) {
                    throw new RetryableUpstreamStatusException(r.statusCode());
                }
                return r;
            });
        } catch (RetryableUpstreamStatusException e) {
            // 重试耗尽：按非 2xx 路径归一（先查 errorMapping 状态区间，再回落 fromHttpStatus）
            return error(collector, mapStatusError(descriptor.errorMapping(), e.status()));
        } catch (Exception e) {
            return error(collector, errorMapper.fromException(e));
        }

        collector.rawResponse(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return error(collector, mapStatusError(descriptor.errorMapping(), response.statusCode()));
        }

        return mapResponse(descriptor, query, response.body(), collector);
    }

    /** 归一错误：记 errorCode 到 collector 并返回降级 MetricValue（单一出口，避免各分支重复记录）。 */
    private static MetricValue error(FetchTraceCollector collector, MetricFetchError err) {
        collector.errorCode(err.tag());
        return MetricValue.error(err.tag());
    }

    /** 构造渲染上下文：payload/params/vars/subjectAttributes 取自 query。 */
    private VariableRenderer.Context context(MetricQuery query) {
        return new VariableRenderer.Context(
                query.subjectId(), query.tenantId(), query.now(),
                query.eventPayload() == null ? Map.of() : query.eventPayload(),
                subMap(query.params().get("params")),
                subMap(query.params().get("vars")),
                query.subjectAttributes() == null ? Map.of() : query.subjectAttributes());
    }

    /** 渲染 method/path/query/headers/body 并应用鉴权，构造 HttpRequest。 */
    private HttpRequest buildRequest(ConnectorDescriptor descriptor,
                                     HttpEndpointRegistry.Endpoint endpoint,
                                     VariableRenderer.Context ctx) {
        HttpRequestTemplate template = descriptor.request();
        String path = renderer.renderTemplate(template.pathTemplate(), ctx);
        String url = endpoint.baseUrl() + path + queryString(template.query(), ctx);

        HttpMethod method = template.method() == null ? HttpMethod.GET : template.method();
        String body = template.bodyTemplate() == null ? null : renderer.renderTemplate(template.bodyTemplate(), ctx);

        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(Math.max(1, descriptor.resilience().readTimeoutMs())));
        switch (method) {
            case GET -> req.GET();
            case POST -> req.POST(bodyPublisher(body));
            case PUT -> req.PUT(bodyPublisher(body));
        }

        if (template.headers() != null) {
            for (TemplateParam h : template.headers()) {
                req.header(h.name(), renderer.renderTemplate(h.valueTemplate(), ctx));
            }
        }
        applyAuth(req, descriptor.auth());
        return req.build();
    }

    /** 应用鉴权方案：三 kind 均按 *Ref 取凭证 / 换 token 注入请求头。 */
    private void applyAuth(HttpRequest.Builder req, AuthScheme auth) {
        if (auth == null) return;
        switch (auth) {
            case StaticHeaderAuth a -> req.header(a.headerName(), credential(a.credentialRef()));
            case BearerAuth a -> req.header("Authorization", "Bearer " + credential(a.tokenRef()));
            case OAuth2ClientCredentialsAuth a -> req.header("Authorization", "Bearer " + oauth2TokenManager.token(a));
        }
    }

    /** 取凭证值，缺失抛 CredentialMissingException（由 fetch 归一为 UNAUTHORIZED）。 */
    private String credential(String ref) {
        String value = credentialStore.get(ref);
        if (value == null) throw new CredentialMissingException(ref);
        return value;
    }

    /** 解析 200 响应：successWhen 判定 → errorMapping → valuePath 取值 → DataTypeCoercion 强转。 */
    private MetricValue mapResponse(ConnectorDescriptor descriptor, MetricQuery query,
                                    String responseBody, FetchTraceCollector collector) {
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (Exception e) {
            return error(collector, MetricFetchError.PARSE_ERROR);
        }

        Predicate successWhen = descriptor.response().successWhen();
        boolean matched = successWhen == null || matches(extractJsonPath(root, successWhen.path()), successWhen);
        collector.successMatched(matched);
        if (!matched) {
            return error(collector, mapEnvelopeError(descriptor.errorMapping(), root, successWhen.path()));
        }

        Object raw = extractJsonPath(root, descriptor.response().valuePath());
        // valuePath 未命中（raw==null）归 PARSE_ERROR，区别于命中后强转失败的 TYPE_MISMATCH
        if (raw == null) return error(collector, MetricFetchError.PARSE_ERROR);

        Object dataType = query.params().get("dataType");
        String dt = dataType == null ? null : dataType.toString();
        Object value = DataTypeCoercion.coerce(raw, dt);
        // 取到非空原始值但强转后为 null = 类型不匹配（与 SQL handler 同款判定，跨源一致）
        if (value == null) return error(collector, MetricFetchError.TYPE_MISMATCH);
        collector.mappedValue(value);
        return new MetricValue(value, dt, ValueSource.FETCHED.tag());
    }

    /**
     * successWhen 不命中时查 errorMapping 的信封码规则，命中用其 to（String 细码名），否则 UPSTREAM_ERROR。
     * 信封码从 successWhen.path 同位取值（ErrorMatch.envelopeCode 契约「与 successWhen.path 同位」）。
     */
    private MetricFetchError mapEnvelopeError(List<ErrorRule> errorMapping, JsonNode root, String envelopePath) {
        if (errorMapping == null) return MetricFetchError.UPSTREAM_ERROR;
        for (ErrorRule rule : errorMapping) {
            ErrorMatch when = rule.when();
            if (when != null && when.envelopeCode() != null
                    && scalarEquals(extractJsonPath(root, envelopePath), when.envelopeCode())) {
                return toFetchError(rule.to());
            }
        }
        return MetricFetchError.UPSTREAM_ERROR;
    }

    /**
     * 非 2xx 状态归一：先查 errorMapping 的状态区间（statusFrom<=status<=statusTo 命中）用其 to，
     * 无命中再回落 {@link MetricFetchErrorMapper#fromHttpStatus}（401/403→UNAUTHORIZED，其余→UPSTREAM_ERROR）。
     */
    private MetricFetchError mapStatusError(List<ErrorRule> errorMapping, int status) {
        if (errorMapping != null) {
            for (ErrorRule rule : errorMapping) {
                ErrorMatch when = rule.when();
                if (when != null && when.statusFrom() != null && when.statusTo() != null
                        && status >= when.statusFrom() && status <= when.statusTo()) {
                    return toFetchError(rule.to());
                }
            }
        }
        return errorMapper.fromHttpStatus(status);
    }

    private static boolean isServerError(int status) {
        return status >= 500 && status <= 599;
    }

    private static boolean retryOnUpstream5xx(ResiliencePolicy policy) {
        return policy.retryOn() != null && policy.retryOn().contains(RetryTrigger.UPSTREAM_5XX);
    }

    /** to（String 细码名）转 MetricFetchError，无法识别归 UPSTREAM_ERROR 并告警（暴露 errorMapping.to 配错）。 */
    private static MetricFetchError toFetchError(String to) {
        try {
            return MetricFetchError.valueOf(to);
        } catch (IllegalArgumentException e) {
            log.warn("connector errorMapping.to 非法细码名 '{}'，回落 UPSTREAM_ERROR——请核对连接器配置", to);
            return MetricFetchError.UPSTREAM_ERROR;
        }
    }

    private static HttpRequest.BodyPublisher bodyPublisher(String body) {
        return body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
    }

    /** 拼 query string：每个 TemplateParam 渲染后（已 URL 编码）拼成 ?k=v&k2=v2。 */
    private String queryString(List<TemplateParam> params, VariableRenderer.Context ctx) {
        if (params == null || params.isEmpty()) return "";
        StringJoiner joiner = new StringJoiner("&", "?", "");
        for (TemplateParam p : params) {
            joiner.add(p.name() + "=" + renderer.renderTemplate(p.valueTemplate(), ctx));
        }
        return joiner.toString();
    }

    /** successWhen 谓词判定：数值走 double 比较，其余仅支持 EQ/NE 的相等判定。 */
    private static boolean matches(Object actual, Predicate predicate) {
        Object expected = predicate.value();
        CompareOp op = predicate.op();
        Double a = asDouble(actual);
        Double b = asDouble(expected);
        if (a != null && b != null) {
            int cmp = Double.compare(a, b);
            return switch (op) {
                case EQ -> cmp == 0;
                case NE -> cmp != 0;
                case GT -> cmp > 0;
                case GE -> cmp >= 0;
                case LT -> cmp < 0;
                case LE -> cmp <= 0;
            };
        }
        boolean equal = scalarEquals(actual, expected);
        return switch (op) {
            case EQ -> equal;
            case NE -> !equal;
            // 非数值不支持大小比较，保守判不命中。
            default -> false;
        };
    }

    private static Double asDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** 标量相等（数值跨类型按 double 比较，其余按字符串比较）。 */
    private static boolean scalarEquals(Object actual, Object expected) {
        if (actual == null || expected == null) return actual == expected;
        Double a = asDouble(actual);
        Double b = asDouble(expected);
        if (a != null && b != null) return Double.compare(a, b) == 0;
        return String.valueOf(actual).equals(String.valueOf(expected));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> subMap(Object raw) {
        return raw instanceof Map ? (Map<String, Object>) raw : Map.of();
    }

    private static long tenantId(String tenantId) {
        try {
            return Long.parseLong(tenantId);
        } catch (NumberFormatException e) {
            return 0L; // 非法 tenantId 归 0，由上层 resolve 查不到对应资源返 NOT_FOUND
        }
    }

    /**
     * 按点号 jsonPath 从 JSON 树取标量值（如 "data.score"）。
     *
     * @param root     JSON 根
     * @param jsonPath 点号路径
     * @return 命中的标量值（Number/String/Boolean）；未命中返回 null
     */
    private static Object extractJsonPath(JsonNode root, String jsonPath) {
        JsonNode cur = root;
        for (String seg : jsonPath.split("\\.")) {
            if (cur == null) return null;
            cur = cur.get(seg);
        }
        if (cur == null || cur.isNull() || cur.isMissingNode()) return null;
        // 容器节点（Object/Array）非标量：jsonPath 配到非叶子，归 null 而非 asString() 的 ""（后者会绕过上层 null 守卫）
        if (cur.isObject() || cur.isArray()) return null;
        if (cur.isIntegralNumber()) return cur.longValue();
        if (cur.isFloatingPointNumber()) return cur.doubleValue();
        if (cur.isBoolean()) return cur.booleanValue();
        return cur.asString();
    }
}
