import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

// LOAD-1: логин -> список товаров -> карточка -> история -> приход -> списание.
// Запуск: BASE_URL=... APP_USERNAME=... APP_PASSWORD=... k6 run load/warehouse.js
// Перед первым прогоном засеять данные: см. load/seed.sh

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const APP_USERNAME = __ENV.APP_USERNAME || 'admin';
const APP_PASSWORD = __ENV.APP_PASSWORD || 'secret';

// Пароль VU-пользователей из load/.vu-password (генерирует seed.sh, в git не попадает) —
// так не нужно передавать его руками между seed.sh и k6 run.
function readSeedPassword() {
    try {
        return open('./.vu-password').trim();
    } catch (e) {
        return null;
    }
}

// Каждый VU — свой пользователь (loadtest-vu-N, см. seed.sh), иначе весь трафик
// записи считается за одного 'admin' и упирается в rate-limit на /api/movements.
const VU_PASSWORD = __ENV.VU_PASSWORD || readSeedPassword() || 'LoadTest123!';
const MAX_VUS = Number(__ENV.MAX_VUS || 50);
// Та же категория, что создаёт load/seed.sh — teardown() трогает только эти товары,
// а не первые попавшиеся в базе (важно на общем стенде с реальными данными).
const LOAD_TEST_CATEGORY = 'LOAD-TEST';

const okStatus = http.expectedStatuses(200);
const receiveStatus = http.expectedStatuses(200, 409, 429);
const writeOffStatus = http.expectedStatuses(200, 409, 422, 429);

export const options = {
    stages: [
        { duration: '30s', target: MAX_VUS },
        { duration: '2m', target: MAX_VUS },
        { duration: '30s', target: 0 },
    ],
    thresholds: {
        list_items_duration: ['p(95)<50'],
        item_detail_duration: ['p(95)<50'],
        history_duration: ['p(95)<50'],
        http_req_duration: ['p(95)<100'],
        http_req_failed: ['rate<0.01'],
        // Базовый уровень троттлинга при текущей per-VU изоляции — 0% (см. прогоны).
        // Порог даёт запас на случай частичной деградации изоляции (VU начнут делить
        // бакет), оставаясь достаточно строгим, чтобы поймать регрессию рано.
        receive_throttled_rate: ['rate<0.05'],
        writeoff_throttled_rate: ['rate<0.05'],
        // 409/422 тоже не должны массово случаться: у каждого VU свой item (не должно
        // быть конфликтов @Version) и большой стартовый остаток (не должно быть 422).
        receive_conflicted_rate: ['rate<0.01'],
        writeoff_conflicted_rate: ['rate<0.01'],
    },
};

const listDuration = new Trend('list_items_duration');
const detailDuration = new Trend('item_detail_duration');
const historyDuration = new Trend('history_duration');
const searchDuration = new Trend('search_items_duration');
const receiveThrottled = new Rate('receive_throttled_rate');
const writeOffThrottled = new Rate('writeoff_throttled_rate');
const receiveConflicted = new Rate('receive_conflicted_rate');
const writeOffConflicted = new Rate('writeoff_conflicted_rate');

function authHeaders(token) {
    return { headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' } };
}

// Синтетический IP на каждый "слот" (VU или номер логина в setup) — RateLimitFilter
// .getClientIp() доверяет X-Forwarded-For, так что каждый выглядит для лимитера
// отдельным IP и не делит один и тот же бакет с остальными.
function fakeIp(slot) {
    return `10.0.${Math.floor(slot / 250)}.${(slot % 250) + 1}`;
}

function vuHeaders(token, responseCallback, idempotencyKey) {
    const headers = {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
        'X-Forwarded-For': fakeIp(__VU),
    };
    if (idempotencyKey) {
        headers['Idempotency-Key'] = idempotencyKey;
    }
    return { headers, responseCallback };
}

function futureIsoDate(daysAhead) {
    return new Date(Date.now() + daysAhead * 24 * 60 * 60 * 1000).toISOString().slice(0, 19);
}

export function setup() {
    const loginRes = http.post(
        `${BASE_URL}/api/auth/login`,
        JSON.stringify({ username: APP_USERNAME, password: APP_PASSWORD }),
        { headers: { 'Content-Type': 'application/json', 'X-Forwarded-For': fakeIp(0) } }
    );
    check(loginRes, { 'login: 200': (r) => r.status === 200 });

    const adminToken = loginRes.json('accessToken');
    if (!adminToken) {
        throw new Error('Логин не вернул accessToken — проверь APP_USERNAME/APP_PASSWORD/BASE_URL');
    }

    const itemsRes = http.get(
        `${BASE_URL}/api/items?page=0&size=50&category=${LOAD_TEST_CATEGORY}`,
        authHeaders(adminToken)
    );
    check(itemsRes, { 'setup items: 200': (r) => r.status === 200 });

    const itemIds = (itemsRes.json('content') || []).map((item) => item.id);
    if (itemIds.length === 0) {
        throw new Error(
            `Нет товаров категории ${LOAD_TEST_CATEGORY} для теста — засиди данные перед прогоном (см. load/seed.sh)`
        );
    }

    // Логиним всех VU-пользователей заранее, здесь, один раз — не полагаемся на то,
    // что JS-состояние переживёт итерации внутри VU (на практике не переживало,
    // и логин долбился на каждой итерации, упираясь в rate-limit самого /login).
    const tokensByVu = {};
    for (let v = 1; v <= MAX_VUS; v++) {
        const username = `loadtest-vu-${v}`;
        const res = http.post(
            `${BASE_URL}/api/auth/login`,
            JSON.stringify({ username, password: VU_PASSWORD }),
            { headers: { 'Content-Type': 'application/json', 'X-Forwarded-For': fakeIp(v) } }
        );
        check(res, { 'vu login: 200': (r) => r.status === 200 });
        const token = res.json('accessToken');
        if (!token) {
            throw new Error(`Логин ${username} не удался — засеян ли пользователь? (см. load/seed.sh)`);
        }
        tokensByVu[v] = token;
    }

    // Один на весь прогон — общий для всех VU (передаётся через data), чтобы повторный
    // запуск теста не попадал в TTL уже использованных Idempotency-Key прошлого прогона
    // (app.idempotency.ttl-hours в application.yml).
    const runId = __ENV.RUN_ID || Date.now().toString();

    return { itemIds, tokensByVu, adminToken, runId };
}

export default function (data) {
    const { itemIds, tokensByVu, runId } = data;
    const token = tokensByVu[__VU];

    const randomItemId = itemIds[Math.floor(Math.random() * itemIds.length)];
    // Для записи (receive/write-off) — свой item на каждый VU, чтобы не создавать
    // искусственную конкуренцию за одну и ту же строку stock (@Version) между VU.
    const myItemId = itemIds[(__VU - 1) % itemIds.length];

    group('list items', () => {
        const res = http.get(`${BASE_URL}/api/items?page=0&size=20`, vuHeaders(token, okStatus));
        listDuration.add(res.timings.duration);
        check(res, { 'list: 200': (r) => r.status === 200 });
    });
    sleep(0.3);

    group('search items', () => {
        const searchTerm = `Item ${Math.floor(Math.random() * itemIds.length) + 1}`;
        const res = http.get(
            `${BASE_URL}/api/items?page=0&size=20&search=${encodeURIComponent(searchTerm)}`,
            vuHeaders(token, okStatus)
        );
        searchDuration.add(res.timings.duration);
        check(res, { 'search: 200': (r) => r.status === 200 });
    });
    sleep(0.3);

    group('item detail', () => {
        const res = http.get(`${BASE_URL}/api/items/${randomItemId}`, vuHeaders(token, okStatus));
        detailDuration.add(res.timings.duration);
        check(res, { 'detail: 200': (r) => r.status === 200 });
    });
    sleep(0.3);

    group('movement history', () => {
        const res = http.get(
            `${BASE_URL}/api/movements/${randomItemId}/history?page=0&size=20`,
            vuHeaders(token, okStatus)
        );
        historyDuration.add(res.timings.duration);
        check(res, { 'history: 200': (r) => r.status === 200 });
    });
    sleep(0.3);

    group('receive', () => {
        const idempotencyKey = `${runId}-vu${__VU}-iter${__ITER}-receive`;
        const res = http.post(
            `${BASE_URL}/api/movements/receive`,
            JSON.stringify({ itemId: myItemId, quantity: 20, expiryDate: futureIsoDate(30) }),
            vuHeaders(token, receiveStatus, idempotencyKey)
        );
        receiveThrottled.add(res.status === 429);
        receiveConflicted.add(res.status === 409);
        check(res, {
            'receive: 200, 409 or 429': (r) => r.status === 200 || r.status === 409 || r.status === 429,
        });
    });
    sleep(0.3);

    group('write-off', () => {
        const idempotencyKey = `${runId}-vu${__VU}-iter${__ITER}-writeoff`;
        const res = http.post(
            `${BASE_URL}/api/movements/write-off`,
            JSON.stringify({ itemId: myItemId, quantity: 5 }),
            vuHeaders(token, writeOffStatus, idempotencyKey)
        );
        writeOffThrottled.add(res.status === 429);
        writeOffConflicted.add(res.status === 409 || res.status === 422);
        check(res, {
            'write-off: 200, 409, 422 or 429': (r) =>
                r.status === 200 || r.status === 409 || r.status === 422 || r.status === 429,
        });
    });
    sleep(0.3);
}

export function teardown(data) {
    const { itemIds, adminToken } = data;
    const headers = authHeaders(adminToken);

    for (const itemId of itemIds) {
        const res = http.post(
            `${BASE_URL}/api/inventory/stocktake`,
            JSON.stringify({ itemId, countedQuantity: 100, surplusExpiryDate: futureIsoDate(30) }),
            headers
        );
        check(res, { 'teardown stocktake: 200': (r) => r.status === 200 });
        if (res.status !== 200) {
            console.error(`Stocktake failed for item ${itemId}: ${res.status}`);
        }
    }
}
