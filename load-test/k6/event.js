import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

// PUSH 摄入压测：/event 入队即返 202，背压由 body.accepted=false 表达（队列满主动拒绝）。
const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const accepted = new Counter('push_accepted');   // 入队成功（持续 ≈ 单线程消费 drain 速率上限）
const rejected = new Counter('push_rejected');    // 队列满被拒（背压）

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
  const url = `${BASE}/api/v1/rule/event`;
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
  check(res, { 'status 202': (r) => r.status === 202 });
  if (res.json('data.accepted') === true) accepted.add(1);
  else rejected.add(1);
}
