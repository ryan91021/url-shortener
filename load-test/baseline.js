// load-test/baseline.js
// ─────────────────────────────────────────────────────────────
// Day 32 · url-shortener baseline load test
//   目的：做一把【可重複】的尺。今天只驗證它跑得起來；
//        Day 33 用同一個檔案跑兩輪（100 RPS / 500 RPS），不改任何一行。
//   跑法：
//     小量驗證（封閉模型）：k6 run --vus 10 --duration 30s load-test/baseline.js
//     小量驗證（開放模型）：k6 run -e RATE=20 -e DURATION=30s load-test/baseline.js
//     Day 33 第一輪：       k6 run load-test/baseline.js                    (100 RPS / 5m)
//     Day 33 第二輪：       k6 run -e RATE=500 load-test/baseline.js        (500 RPS / 5m)
//   前置：export API_KEY=$(aws secretsmanager get-secret-value \
//           --secret-id url-shortener/api-key --query SecretString --output text | jq -r .apiKey)
// ─────────────────────────────────────────────────────────────
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    scenarios: {
        constant_load: {
            // ★★ 開放模型：固定「每秒進來幾個請求」，服務再慢也照丟。
            //    （對照 --vus 是封閉模型：服務越慢，你打得越輕 → 量不到真正的極限）
            executor: 'constant-arrival-rate',
            rate: Number(__ENV.RATE || 100),      // ★ 參數化：Day 33 用 -e RATE=500 就好，不改檔
            timeUnit: '1s',
            duration: __ENV.DURATION || '5m',
            preAllocatedVUs: 50,                  // ★ 需要的 VU ≈ rate × 每次 iteration 耗時（Little's Law）
            maxVUs: 300,                          // ★ 不夠時 k6 會補到這個上限；再不夠 → dropped_iterations
        },
    },
    thresholds: {
        // ⚠️ 單位是【毫秒】—— CloudWatch 的 TargetResponseTime 是【秒】，兩邊差 1000 倍
        http_req_duration: ['p(95)<500', 'p(99)<1000'],
        http_req_failed: ['rate<0.01'],         // ★ k6 預設把 200–399 當成功 ⇒ 302 / 201 都算過

        // ★★ Day 33 新增：讓摘要【分開】印出讀路徑與寫路徑的百分位
        //    （只影響「怎麼report」，不影響「打出去什麼流量」——所以兩輪仍然是同一把尺）
        'http_req_duration{name:GET /api/v1/{code}}':   ['p(95)<500'],
        'http_req_duration{name:POST /api/v1/shorten}': ['p(95)<800'],
    },
};

// ★ ALB 只有 :80 HTTP listener（Week 6 沒做 TLS）⇒ 一定是 http://
const RAW = __ENV.ALB_DNS || 'url-shortener-alb-1273005183.ap-east-2.elb.amazonaws.com';
const ALB_DNS = RAW.startsWith('http') ? RAW : `http://${RAW}`;

// ★ 用真實存在的短碼（用 aws dynamodb scan 查出來的，不要手打）
const shortCodes = [
    'q6w83nb',
    'qm90t53',
    'qspq70c',
    'rrrtxm4',
    '9fd6noh',
    'x5l1h6j',
    'ivu46b2',
    '25esld0',
    'am6vckv',
    '1mtdm5m',
    // …把 2.2 ③ 查出來的另外 7 個貼進來…
];

export default function () {
    const r = Math.random();

    if (r < 0.7) {
        // ── 70% 讀路徑：GET /api/v1/{shortCode} → 302 ──────────────
        const code = shortCodes[Math.floor(Math.random() * shortCodes.length)];
        const res = http.get(`${ALB_DNS}/api/v1/${code}`, {
            redirects: 0,                                  // ★★ 不要跟著跳！見下方說明
            tags: { name: 'GET /api/v1/{code}' },          // ★★ 收斂高基數：預設 name tag 是完整 URL
        });
        check(res, { 'GET 302': (res) => res.status === 302 });
    } else {
        // ── 30% 寫路徑：POST /api/v1/shorten → 201 ────────────────
        const res = http.post(
            `${ALB_DNS}/api/v1/shorten`,
            JSON.stringify({ longUrl: `https://example.com/day32-${Math.random()}` }),
            {
                headers: {
                    'Content-Type': 'application/json',
                    'X-API-Key': __ENV.API_KEY,                // ★ 沒帶 → 401 → threshold 紅 → exit 99
                },
                tags: { name: 'POST /api/v1/shorten' },
            }
        );
        check(res, { 'POST 201': (res) => res.status === 201 });   // ★★ 201，不是 200
    }

    sleep(0.1);   // ★ think time。在 arrival-rate 下【不影響 RPS】，只影響需要幾個 VU
}