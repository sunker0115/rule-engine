package com.sstlfsj.rule.eval.internal.metric.http;

import com.sstlfsj.rule.config.api.connector.OAuth2ClientCredentialsAuth;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OAuth2 client-credentials token 管理：按 *Ref 取凭证换取 access_token 并缓存。
 * 缓存键 = tokenUrl|clientIdRef|scopes；过期 = now + expires_in - 30s 安全边际。
 * token 交换属基础设施时钟，用 {@link Instant#now()}，非引擎统一时钟。
 */
@Component
public class OAuth2TokenManager {

    /** token 交换连接超时（不在评估热路径预算内，仍设上限）。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    /** token 交换读超时。 */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    /** 过期安全边际：提前 30s 视为过期，避免临界点用到将失效 token。 */
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(30);

    private record CachedToken(String accessToken, Instant expiresAt) {}

    private final CredentialStore credentialStore;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Map<String, CachedToken> cache = new ConcurrentHashMap<>();

    public OAuth2TokenManager(CredentialStore credentialStore, ObjectMapper objectMapper) {
        this.credentialStore = credentialStore;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    /**
     * 取 client-credentials access_token：缓存命中且未过期返缓存，否则换取并缓存。
     *
     * @param auth OAuth2 鉴权配置（tokenUrl/clientIdRef/clientSecretRef/scopes）
     * @return access_token
     * @throws CredentialMissingException clientId/clientSecret 凭证未配置
     */
    public String token(OAuth2ClientCredentialsAuth auth) {
        List<String> scopes = auth.scopes() == null ? List.of() : auth.scopes();
        String key = auth.tokenUrl() + "|" + auth.clientIdRef() + "|" + String.join(",", scopes);
        // compute 对同一 key 持 bin 锁，串行化该 key 的换取与过期替换，避免并发惊群式重复换 token。
        // 取舍：回调内阻塞 token 请求期间同 key 其它线程会等待——token 端点 key 基数小，可接受。
        CachedToken token = cache.compute(key, (k, existing) -> {
            if (existing != null && Instant.now().isBefore(existing.expiresAt())) {
                return existing;
            }
            return exchange(auth, scopes);
        });
        return token.accessToken();
    }

    private CachedToken exchange(OAuth2ClientCredentialsAuth auth, List<String> scopes) {
        String clientId = credentialStore.get(auth.clientIdRef());
        if (clientId == null) throw new CredentialMissingException(auth.clientIdRef());
        String clientSecret = credentialStore.get(auth.clientSecretRef());
        if (clientSecret == null) throw new CredentialMissingException(auth.clientSecretRef());

        StringBuilder form = new StringBuilder("grant_type=client_credentials");
        if (!scopes.isEmpty()) {
            form.append("&scope=").append(URLEncoder.encode(String.join(" ", scopes), StandardCharsets.UTF_8));
        }
        String basic = Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(auth.tokenUrl()))
                .timeout(READ_TIMEOUT)
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                .build();
        try {
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("token endpoint returned " + resp.statusCode());
            }
            JsonNode body = objectMapper.readTree(resp.body());
            String accessToken = body.path("access_token").asString();
            long expiresIn = body.path("expires_in").asLong();
            // 200 但响应体异常时缺 access_token/expires_in 会缓存空 Bearer 且立即过期，
            // 错误被推迟到下游带空 token 请求才暴露；此处即时抛错。异常消息只放 tokenUrl，绝不 dump body（body 可能含 token/secret）。
            if (accessToken == null || accessToken.isBlank()) {
                throw new IllegalStateException("token response missing access_token: " + auth.tokenUrl());
            }
            if (expiresIn <= 0) {
                throw new IllegalStateException("token response missing expires_in: " + auth.tokenUrl());
            }
            Instant expiresAt = Instant.now().plusSeconds(expiresIn).minus(EXPIRY_MARGIN);
            return new CachedToken(accessToken, expiresAt);
        } catch (CredentialMissingException | IllegalStateException e) {
            // 凭证缺失与响应体校验失败的异常已含精确语义，原样上抛，不再包成泛化消息（也避免覆盖 access_token 等关键字）。
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("oauth2 token exchange failed: " + auth.tokenUrl(), e);
        }
    }
}
