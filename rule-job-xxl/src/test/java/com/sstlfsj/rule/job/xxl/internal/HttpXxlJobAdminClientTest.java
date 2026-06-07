package com.sstlfsj.rule.job.xxl.internal;

import com.sstlfsj.rule.job.xxl.XxlJobProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** 用本地 stub admin（com.sun.net.httpserver）验证 HttpXxlJobAdminClient 的登录与 seed 行为。 */
class HttpXxlJobAdminClientTest {

    private HttpServer server;
    private String base;
    /** 记录每个 path 收到的请求次数，供断言"有了不管"是否发了写请求。 */
    private final Map<String, Integer> hits = new ConcurrentHashMap<>();
    /** 各 path 的预置响应体（JSON）。 */
    private final Map<String, String> responses = new ConcurrentHashMap<>();
    /** 记录 jobinfo/pageList 请求体，便于断言过滤参数。 */
    private final List<String> capturedBodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/xxl-job-admin/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/xxl-job-admin";
        // 默认：登录成功返回 token
        responses.put("/xxl-job-admin/auth/doLogin", "{\"code\":200,\"msg\":null,\"data\":\"tok-123\"}");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        hits.merge(path, 1, Integer::sum);
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        capturedBodies.add(path + "?" + body);
        String resp = responses.getOrDefault(path, "{\"code\":500,\"msg\":\"no stub\",\"data\":null}");
        byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private HttpXxlJobAdminClient client() {
        XxlJobProperties p = new XxlJobProperties();
        p.setAdminAddresses(base);
        p.setAppname("rule-engine");
        p.setAdminUsername("admin");
        p.setAdminPassword("pwd");
        return new HttpXxlJobAdminClient(p, JsonMapper.builder().build());
    }

    @Test
    void seedLogsInBeforeAdminCall() {
        // group 已存在 + jobinfo 已存在 → 不应发写请求，但应已登录
        responses.put("/xxl-job-admin/jobgroup/pageList",
                "{\"code\":200,\"data\":{\"data\":[{\"id\":7,\"appname\":\"rule-engine\"}],\"total\":1}}");
        responses.put("/xxl-job-admin/jobinfo/pageList",
                "{\"code\":200,\"data\":{\"data\":[{\"id\":42,\"executorHandler\":\"job:1\"}],\"total\":1}}");

        long id = client().ensureJobSeeded("job:1", "0 0 * * * ?");

        assertThat(id).isEqualTo(42L);
        assertThat(hits).containsKey("/xxl-job-admin/auth/doLogin");
        assertThat(hits.getOrDefault("/xxl-job-admin/jobinfo/insert", 0)).isZero();
    }
}
