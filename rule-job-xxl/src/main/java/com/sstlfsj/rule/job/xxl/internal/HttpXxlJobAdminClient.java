package com.sstlfsj.rule.job.xxl.internal;

import com.sstlfsj.rule.job.xxl.XxlJobProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * XxlJobAdminClient 的 HTTP 实现：JDK HttpClient + 注入的全局 ObjectMapper，form-urlencoded 调 admin REST。
 *
 * <p>登录态：懒登录（首个需鉴权请求触发），cookie 失效（code!=200 且含登录提示）时清 token 重试一次；
 * 登录本身失败最多重试 {@value #LOGIN_MAX_RETRY} 次。seed 语义"有了不管"见 {@link #ensureJobSeeded}。
 */
public class HttpXxlJobAdminClient implements XxlJobAdminClient {

    private static final Logger log = LoggerFactory.getLogger(HttpXxlJobAdminClient.class);
    private static final int LOGIN_MAX_RETRY = 3;
    private static final int SUCCESS_CODE = 200;
    /** SSO token 的 cookie 名（admin 默认 tokenKey；若 admin 自定义需同步） */
    private static final String SSO_TOKEN_COOKIE = "xxl_sso_token";

    private final XxlJobProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String adminBase;
    private volatile String token;

    public HttpXxlJobAdminClient(XxlJobProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        // seed 一次即可（多 admin 实例共享库），取第一个地址
        this.adminBase = props.getAdminAddresses().split(",")[0].trim();
    }

    @Override
    public synchronized long ensureJobSeeded(String handlerName, String cron) {
        int groupId = ensureJobGroup();
        Long existing = findJobInfoId(groupId, handlerName);
        if (existing != null) {
            log.info("xxl-job admin 已存在 job handler={} id={}，保持不动（有了不管）", handlerName, existing);
            return existing;
        }
        return insertJobInfo(groupId, handlerName, cron);
    }

    // ---- Task 4 补全 group / jobinfo 逻辑；本任务先留桩占位，保证编译 ----

    private int ensureJobGroup() {
        throw new UnsupportedOperationException("Task 4 实现");
    }

    private Long findJobInfoId(int groupId, String handlerName) {
        throw new UnsupportedOperationException("Task 4 实现");
    }

    private long insertJobInfo(int groupId, String handlerName, String cron) {
        throw new UnsupportedOperationException("Task 4 实现");
    }

    /** form-urlencoded POST；withAuth=true 带登录 cookie，遇登录失效清 token 重试一次。成功返回响应 JSON 根。 */
    JsonNode post(String path, Map<String, String> form, boolean withAuth) {
        JsonNode root = doPost(path, form, withAuth);
        if (withAuth && root.path("code").asInt() != SUCCESS_CODE && looksLikeAuthFailure(root)) {
            token = null;
            root = doPost(path, form, true);
        }
        if (root.path("code").asInt() != SUCCESS_CODE) {
            throw new IllegalStateException("xxl-job admin " + path + " 失败: " + root.path("msg").asString(""));
        }
        return root;
    }

    private JsonNode doPost(String path, Map<String, String> form, boolean withAuth) {
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(URI.create(adminBase + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(encode(form)));
            if (withAuth) {
                req.header("Cookie", SSO_TOKEN_COOKIE + "=" + token());
            }
            HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            return mapper.readTree(resp.body());
        } catch (IOException e) {
            throw new IllegalStateException("xxl-job admin 通信失败: " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("xxl-job admin 通信中断: " + path, e);
        }
    }

    private boolean looksLikeAuthFailure(JsonNode root) {
        String msg = root.path("msg").asString("");
        return msg.contains("登录") || msg.contains("login") || root.path("code").asInt() == 401;
    }

    /** 懒登录：POST /auth/doLogin 取 Response.data 作为 sso token，失败重试 LOGIN_MAX_RETRY 次。 */
    private String token() {
        String t = token;
        if (t != null) {
            return t;
        }
        for (int attempt = 1; attempt <= LOGIN_MAX_RETRY; attempt++) {
            JsonNode root = doPost("/auth/doLogin",
                    form("userName", props.getAdminUsername(), "password", props.getAdminPassword()), false);
            if (root.path("code").asInt() == SUCCESS_CODE) {
                token = root.path("data").asString();
                return token;
            }
            log.warn("xxl-job admin 登录失败({}/{}): {}", attempt, LOGIN_MAX_RETRY, root.path("msg").asString(""));
        }
        throw new IllegalStateException("xxl-job admin 登录失败，重试 " + LOGIN_MAX_RETRY + " 次");
    }

    static Map<String, String> form(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String encode(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
