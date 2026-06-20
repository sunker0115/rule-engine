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
    /** 各 path 的预置响应体（JSON）；null → 用动态 handler 返回。 */
    private final Map<String, String> responses = new ConcurrentHashMap<>();
    /** 记录 jobinfo/pageList 请求体，便于断言过滤参数。 */
    private final List<String> capturedBodies = new CopyOnWriteArrayList<>();
    /** 按 path 计数供动态响应（如 pageList 首次空、第二次有数据）。 */
    private final Map<String, Integer> countByPath = new ConcurrentHashMap<>();

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
        countByPath.merge(path, 1, Integer::sum);
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        capturedBodies.add(path + "?" + body);
        // 动态响应：jobgroup/pageList 首次空 → 第二次返回有数据(不缓存,按调用次数动态算)
        String resp;
        if ("/xxl-job-admin/jobgroup/pageList".equals(path) && responses.get(path) == null) {
            int callN = countByPath.getOrDefault(path, 0);
            resp = callN == 1
                    ? "{\"code\":200,\"data\":{\"data\":[],\"total\":0}}"
                    : "{\"code\":200,\"data\":{\"data\":[{\"id\":15,\"appname\":\"rule-engine\"}],\"total\":1}}";
        } else {
            resp = responses.getOrDefault(path, "{\"code\":500,\"msg\":\"no stub\",\"data\":null}");
        }
        byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        // 3.4.x 登录认证走 cookie:Set-Cookie xxl_job_login_token
        if ("/xxl-job-admin/auth/doLogin".equals(path)) {
            ex.getResponseHeaders().add("Set-Cookie", "xxl_job_login_token=tok-123; Path=/; HttpOnly");
        }
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
        // group 已存在 + jobinfo 已存在（按 jobDesc 命中）→ 不应发写请求，但应已登录
        responses.put("/xxl-job-admin/jobgroup/pageList",
                "{\"code\":200,\"data\":{\"data\":[{\"id\":7,\"appname\":\"rule-engine\"}],\"total\":1}}");
        responses.put("/xxl-job-admin/jobinfo/pageList",
                "{\"code\":200,\"data\":{\"data\":[{\"id\":42,\"jobDesc\":\"task-1\"}],\"total\":1}}");

        long id = client().ensureJobSeeded("task-1", "scheduled-task-runner", "0 0 * * * ?", "1");

        assertThat(id).isEqualTo(42L);
        assertThat(hits).containsKey("/xxl-job-admin/auth/doLogin");
        assertThat(hits.getOrDefault("/xxl-job-admin/jobinfo/insert", 0)).isZero();
    }

    @Test
    void seedInsertsWhenAbsentAndReturnsNewId() {
        responses.put("/xxl-job-admin/jobgroup/pageList",
                "{\"code\":200,\"data\":{\"data\":[{\"id\":7,\"appname\":\"rule-engine\"}],\"total\":1}}");
        // jobinfo 列表为空 → 视为不存在
        responses.put("/xxl-job-admin/jobinfo/pageList",
                "{\"code\":200,\"data\":{\"data\":[],\"total\":0}}");
        responses.put("/xxl-job-admin/jobinfo/insert",
                "{\"code\":200,\"msg\":null,\"data\":99}");

        long id = client().ensureJobSeeded("task-1", "scheduled-task-runner", "0 0 * * * ?", "1");

        assertThat(id).isEqualTo(99L);
        assertThat(hits.getOrDefault("/xxl-job-admin/jobinfo/insert", 0)).isEqualTo(1);
        // insert 请求体应带通用 handler 与 executorParam（= taskId）
        String insertBody = capturedBodies.stream()
                .filter(b -> b.startsWith("/xxl-job-admin/jobinfo/insert?"))
                .findFirst().orElseThrow();
        assertThat(insertBody).contains("executorHandler=scheduled-task-runner");
        assertThat(insertBody).contains("executorParam=1");
        assertThat(insertBody).contains("jobDesc=task-1");
    }

    @Test
    void seedFuzzyMatchIsRefinedByExactEquals() {
        // pageList 按 jobDesc 模糊匹配返回了前缀相近但不相等的行 → 客户端精确 equals 应判为不存在 → insert
        responses.put("/xxl-job-admin/jobgroup/pageList",
                "{\"code\":200,\"data\":{\"data\":[{\"id\":7,\"appname\":\"rule-engine\"}],\"total\":1}}");
        responses.put("/xxl-job-admin/jobinfo/pageList",
                "{\"code\":200,\"data\":{\"data\":[{\"id\":42,\"jobDesc\":\"task-10\"}],\"total\":1}}");
        responses.put("/xxl-job-admin/jobinfo/insert",
                "{\"code\":200,\"data\":100}");

        long id = client().ensureJobSeeded("task-1", "scheduled-task-runner", "0 0 * * * ?", "1");

        assertThat(id).isEqualTo(100L);
        assertThat(hits.getOrDefault("/xxl-job-admin/jobinfo/insert", 0)).isEqualTo(1);
    }

    @Test
    void seedCreatesJobGroupWhenAbsent() {
        // 不预设 jobgroup/pageList(will be dynamic: 首次空 → 第二次有数据)
        responses.put("/xxl-job-admin/jobgroup/insert",
                "{\"code\":200,\"data\":null}");  // 3.4.x insert 返回 null
        responses.put("/xxl-job-admin/jobinfo/pageList",
                "{\"code\":200,\"data\":{\"data\":[],\"total\":0}}");
        responses.put("/xxl-job-admin/jobinfo/insert",
                "{\"code\":200,\"data\":101}");

        long id = client().ensureJobSeeded("task-1", "scheduled-task-runner", "0 0 * * * ?", "1");

        assertThat(id).isEqualTo(101L);
        assertThat(hits.getOrDefault("/xxl-job-admin/jobgroup/insert", 0)).isEqualTo(1);
        // 查了两次 pageList:首次空、insert 后重查
        assertThat(hits.getOrDefault("/xxl-job-admin/jobgroup/pageList", 0)).isEqualTo(2);
    }
}
