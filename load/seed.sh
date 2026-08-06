#!/usr/bin/env bash
# LOAD-1: наполняет базу тестовыми товарами + начальным приходом перед k6-прогоном.
# Использование: BASE_URL=http://localhost:8080 APP_USERNAME=admin APP_PASSWORD=secret ITEM_COUNT=50 MAX_VUS=50 ./load/seed.sh

set -u

BASE_URL="${BASE_URL:-http://localhost:8080}"
APP_USERNAME="${APP_USERNAME:-admin}"
APP_PASSWORD="${APP_PASSWORD:-secret}"
ITEM_COUNT="${ITEM_COUNT:-50}"
MAX_VUS="${MAX_VUS:-50}"
CATEGORY="LOAD-TEST"
VU_PASSWORD_FILE="$(dirname "$0")/.vu-password"

# Пароль VU-пользователей не хардкодим. Приоритет:
# 1) явно передан через env — используем его;
# 2) уже есть load/.vu-password с прошлого запуска — переиспользуем (пользователи
#    loadtest-vu-* создаются один раз, POST /api/v1/users для уже существующих -
#    no-op, так что новый случайный пароль тут только сломает логин в k6);
# 3) иначе — генерируем случайный и сохраняем на будущее (в git не попадает,
#    см. .gitignore).
if [ -n "${VU_PASSWORD:-}" ]; then
  : # использовать явно переданный
elif [ -f "$VU_PASSWORD_FILE" ]; then
  VU_PASSWORD="$(cat "$VU_PASSWORD_FILE")"
  echo "Использую уже сохранённый пароль VU-пользователей из $VU_PASSWORD_FILE."
else
  VU_PASSWORD="$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')Aa1!"
  echo "Пароль VU-пользователей сгенерирован случайно, сохранён в $VU_PASSWORD_FILE (не в git)."
fi
printf '%s' "$VU_PASSWORD" > "$VU_PASSWORD_FILE"
# Уникально на запуск скрипта — чтобы повторный сид не попал в TTL уже
# использованных Idempotency-Key прошлого запуска (app.idempotency.ttl-hours).
SEED_RUN_ID="seed-$(date +%s)-$$"

echo "Логин как $APP_USERNAME..."
TOKEN=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$APP_USERNAME\",\"password\":\"$APP_PASSWORD\"}" \
  | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)

if [ -z "$TOKEN" ]; then
  echo "Не удалось получить accessToken. Проверь APP_USERNAME/APP_PASSWORD/BASE_URL."
  exit 1
fi

echo "Создаю $MAX_VUS тестовых пользователей (по одному на VU, чтобы не упереться в rate-limit по username)..."
for v in $(seq 1 "$MAX_VUS"); do
  USER_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/users" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"loadtest-vu-$v\",\"password\":\"$VU_PASSWORD\",\"role\":\"ROLE_ADMIN\"}")

  case "$USER_STATUS" in
    201) echo "  loadtest-vu-$v создан" ;;
    409) echo "  loadtest-vu-$v уже существует, пропускаю" ;;
    *) echo "  loadtest-vu-$v: ОШИБКА создания (HTTP $USER_STATUS)" ;;
  esac
done

echo "Создаю категорию $CATEGORY (если ещё нет)..."
curl -s -X POST "$BASE_URL/api/v1/categories" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"$CATEGORY\"}" > /dev/null

echo "Создаю $ITEM_COUNT товаров с начальным приходом..."
EXPIRY=$(date -u -d "+30 days" +"%Y-%m-%dT%H:%M:%S")
CREATED=0

for i in $(seq 1 "$ITEM_COUNT"); do
  RESP=$(curl -s -X POST "$BASE_URL/api/v1/items" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"sku\":\"SKU-LOAD-$i\",\"name\":\"Load Test Item $i\",\"category\":\"$CATEGORY\",\"minStock\":0,\"price\":100.00,\"cost\":50.00}")

  ITEM_ID=$(echo "$RESP" | grep -o '"id":[0-9]*' | head -1 | cut -d':' -f2)

  if [ -z "$ITEM_ID" ]; then
    echo "  [$i] пропущен (вероятно, SKU уже существует)"
    continue
  fi

  RECEIVE_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/movements/receive" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: ${SEED_RUN_ID}-item${i}-receive" \
    -d "{\"itemId\":$ITEM_ID,\"quantity\":100,\"expiryDate\":\"$EXPIRY\"}")

  if [ "$RECEIVE_STATUS" != "200" ]; then
    echo "  [$i] item id=$ITEM_ID создан, но приход не прошёл (HTTP $RECEIVE_STATUS)"
    continue
  fi

  CREATED=$((CREATED + 1))
  echo "  [$i] item id=$ITEM_ID создан, приход 100 шт."
done

echo "Готово. Создано новых товаров: $CREATED из $ITEM_COUNT."
