import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

// 取数路径压测：规则依赖 SQL_AGGREGATE 指标 demo.agg（allowProvided=false），请求不传该指标
// → 请求线程内同步 fetch（命名只读源 loadtest_ro，SQL "SELECT 100"，cache_ttl=60s）。
// SUBJECT_MODE=warm：subjectId=s-${VU}（≤VU 个 → 预热后缓存命中）；
// SUBJECT_MODE=cold：subjectId 每请求唯一（缓存恒穿透 → 每请求 1 次 SQL 往返）。
const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const MODE = __ENV.SUBJECT_MODE || 'warm';
const hits = new Counter('rule_hits');   // == http_reqs 表示 fetch 成功(demo.agg=100>=0 命中)

export const options = {
  scenarios: {
    ramp: {
      executor: 'ramping-vus',
      startVUs: 10,
      stages: [
        { duration: '30s', target: 25 },
        { duration: '30s', target: 50 },
        { duration: '30s', target: 100 },
        { duration: '30s', target: 200 },
        { duration: '30s', target: 400 },
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: { http_req_failed: ['rate<0.01'] },
};

export default function () {
  const url = `${BASE}/api/v1/rule/evaluate`;
  const subjectId = MODE === 'cold'
    ? `s-${__VU}-${__ITER}-${Date.now()}`   // 唯一 → 缓存穿透
    : `s-${__VU}`;                           // 有界 → 预热后命中
  const body = JSON.stringify({
    tenantCode: 'loadtest',
    sceneCode: 'loadtest',
    eventType: 'login',
    subjectId: subjectId,
    eventId: `${__VU}-${__ITER}-${Date.now()}`,
    payload: {},
    providedMetrics: {},   // 不传 demo.agg → 强制 fetch
  });
  const res = http.post(url, body, { headers: { 'Content-Type': 'application/json' } });
  const ok = check(res, {
    'status 200': (r) => r.status === 200,
    'ruleHit true': (r) => r.json('data.ruleHit') === true,
  });
  if (res.json('data.ruleHit') === true) hits.add(1);
}
