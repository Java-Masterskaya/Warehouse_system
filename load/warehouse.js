import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Trend } from 'k6/metrics';

// LOAD-1: логин -> список товаров -> карточка -> история -> приход -> списание.
// Запуск: BASE_URL=... APP_USERNAME=... APP_PASSWORD=... k6 run load/warehouse.js
// Перед первым прогоном засеять данные: см. load/seed.sh

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const APP_USERNAME = __ENV.APP_USERNAME || 'admin';
const APP_PASSWORD = __ENV.APP_PASSWORD || 'secret';
// Каждый VU логинится под своим пользователем (loadtest-vu-N, см. load/seed.sh) —
// иначе весь трафик записи считается за одного 'admin' и упирается в rate-limit
// на /api/movements (rate-limiting.movements.username в application.yml).
const VU_PASSWORD = __ENV.VU_PASSWORD || 'LoadTest123!';
const MAX_VUS = Number(__ENV.MAX_VUS || 10);

export const options = {
    stages: [
        { duration: '30s', target: MAX_VUS },
        { duration: '2m', target: MAX_VUS },
        { duration: '30s', target: 0 },
    ],
    // Зафиксировано по итогам первого чистого прогона (p95 list/detail/history
    // ~6-12ms локально) с запасом ~5-10x на CI/прод-окружение и реальный объём данных.
    thresholds: {
        list_items_duration: ['p(95)<50'],
        item_detail_duration: ['p(95)<50'],
        history_duration: ['p(95)<50'],
        http_req_duration: ['p(95)<100'],
    },
};

const listDuration = new Trend('list_items_duration');
const detailDuration = new Trend('item_detail_duration');
const historyDuration = new Trend('history_duration');

function authHeaders(token) {
    return { headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' } };
}

// Синтетический IP на каждый "слот" (VU или номер логина в setup) — RateLimitFilter
// .getClientIp() доверяет X-Forwarded-For, так что каждый выглядит для лимитера
// отдельным IP и не делит один и тот же бакет с остальными.
function fakeIp(slot) {
    return `10.0.${Math.floor(slot / 250)}.${(slot % 250) + 1}`;
}

function vuHeaders(token) {
    return {
        headers: {
            Authorization: `Bearer ${token}`,
            'Content-Type': 'application/json',
            'X-Forwarded-For': fakeIp(__VU),
        },
    };
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

    const token = loginRes.json('accessToken');
    if (!token) {
        throw new Error('Логин не вернул accessToken — проверь APP_USERNAME/APP_PASSWORD/BASE_URL');
    }

    const itemsRes = http.get(`${BASE_URL}/api/items?page=0&size=50`, authHeaders(token));
    check(itemsRes, { 'setup items: 200': (r) => r.status === 200 });

    const itemIds = (itemsRes.json('content') || []).map((item) => item.id);
    if (itemIds.length === 0) {
        throw new Error('Нет товаров для теста — засиди данные перед прогоном (см. LOAD-1: сидинг)');
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

    return { itemIds, tokensByVu };
}

export default function (data) {
    const { itemIds, tokensByVu } = data;
    const headers = vuHeaders(tokensByVu[__VU]);

    // Для чтения — случайный item из общего пула, конфликтов тут не бывает.
    const randomItemId = itemIds[Math.floor(Math.random() * itemIds.length)];
    // Для записи (receive/write-off) — свой item на каждый VU, чтобы не создавать
    // искусственную конкуренцию за одну и ту же строку stock (@Version) между VU.
    const myItemId = itemIds[(__VU - 1) % itemIds.length];

    group('list items', () => {
        const res = http.get(`${BASE_URL}/api/items?page=0&size=20`, headers);
        listDuration.add(res.timings.duration);
        check(res, { 'list: 200': (r) => r.status === 200 });
    });
    sleep(1);

    group('item detail', () => {
        const res = http.get(`${BASE_URL}/api/items/${randomItemId}`, headers);
        detailDuration.add(res.timings.duration);
        check(res, { 'detail: 200': (r) => r.status === 200 });
    });
    sleep(1);

    group('movement history', () => {
        const res = http.get(`${BASE_URL}/api/movements/${randomItemId}/history?page=0&size=20`, headers);
        historyDuration.add(res.timings.duration);
        check(res, { 'history: 200': (r) => r.status === 200 });
    });
    sleep(1);

    group('receive', () => {
        const res = http.post(
            `${BASE_URL}/api/movements/receive`,
            JSON.stringify({ itemId: myItemId, quantity: 20, expiryDate: futureIsoDate(30) }),
            headers
        );
        // 409 допустим: даже при партиционировании по VU остаётся редкий шанс
        // конкуренции (например, с уже идущим прогоном) — это не баг скрипта.
        check(res, { 'receive: 200 or 409': (r) => r.status === 200 || r.status === 409 });
    });
    sleep(1);

    group('write-off', () => {
        const res = http.post(
            `${BASE_URL}/api/movements/write-off`,
            JSON.stringify({ itemId: myItemId, quantity: 5 }),
            headers
        );
        check(res, {
            'write-off: 200, 409 or 422': (r) => r.status === 200 || r.status === 409 || r.status === 422,
        });
    });
    sleep(1);
}
