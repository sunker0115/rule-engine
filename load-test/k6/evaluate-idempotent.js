import http from 'k6/http';
import { check } from 'k6';

// 幂等去重压测：有界 eventId 空间 + 速率低于 action-delivery drain 上限(2500/s)，
// 让消费侧 keep-up，从而 action_execution 落库率不被 best-effort 丢弃掩盖。
// 同一 eventId 反复并发命中 → 期望每个幂等键(tenant:eventId:PASS:SEND_ALERT)恰好 1 行。
const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const RUNID = __ENV.RUNID || `idem-${Date.now()}`;
const ID_SPACE = parseInt(__ENV.ID_SPACE || '200', 10);
const RATE = parseInt(__ENV.RATE || '1000', 10);
const DURATION = __ENV.DURATION || '40s';

export const options = {
  scenarios: {
    dup: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 100,
      maxVUs: 300,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  // 在有界空间内随机取键 → 大量跨请求重复，触发去重
  const n = Math.floor(Math.random() * ID_SPACE);
  const eventId = `${RUNID}-${n}`;
  const body = JSON.stringify({
    tenantCode: 'loadtest',
    sceneCode: 'loadtest',
    eventType: 'login',
    subjectId: `s-${n}`,
    eventId: eventId,
    payload: { amount: 100 },
  });
  const res = http.post(`${BASE}/api/v1/rule/evaluate`, body,
    { headers: { 'Content-Type': 'application/json' } });
  check(res, {
    'status 200': (r) => r.status === 200,
    'ruleHit true': (r) => r.json('data.ruleHit') === true,
  });
}
