import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const hits = new Counter('rule_hits');

// 阶梯加压：每档平台观察拐点
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
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<200', 'p(99)<500'],  // 仅报警线，探顶不强制
  },
};

export default function () {
  const url = `${BASE}/api/v1/rule/evaluate`;
  // eventId 每次唯一 → 走真实写路径，不触发幂等去重
  const eventId = `${__VU}-${__ITER}-${Date.now()}`;
  const body = JSON.stringify({
    tenantCode: 'loadtest',
    sceneCode: 'loadtest',
    eventType: 'login',
    subjectId: `s-${__VU}`,
    eventId: eventId,
    payload: {},
    providedMetrics: { 'demo.score': 100 },
  });
  const res = http.post(url, body, { headers: { 'Content-Type': 'application/json' } });
  const ok = check(res, {
    'status 200': (r) => r.status === 200,
    'ruleHit true': (r) => r.json('data.ruleHit') === true,
  });
  if (ok) hits.add(1);
}
