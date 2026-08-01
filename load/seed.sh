#!/usr/bin/env bash
# LOAD-1: наполняет базу тестовыми товарами + начальным приходом перед k6-прогоном.
# Использование: BASE_URL=http://localhost:8080 APP_USERNAME=admin APP_PASSWORD=secret ITEM_COUNT=50 MAX_VUS=50 ./load/seed.sh

set -u

BASE_URL="${BASE_URL:-http://localhost:8080}"
APP_USERNAME="${APP_USERNAME:-admin}"
APP_PASSWORD="${APP_PASSWORD:-secret}"
ITEM_COUNT="${ITEM_COUNT:-50}"
MAX_VUS="${MAX_VUS:-50}"
VU_PASSWORD="${VU_PASSWORD:-LoadTest123!}"
CATEGORY="LOAD-TEST"

echo "Логин как $APP_USERNAME..."
TOKEN=$(curl -s -X POST "$BASE_URL/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$APP_USERNAME\",\"password\":\"$APP_PASSWORD\"}" \
  | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)

if [ -z "$TOKEN" ]; then
  echo "Не удалось получить accessToken. Проверь APP_USERNAME/APP_PASSWORD/BASE_URL."
  exit 1
fi

echo "Создаю $MAX_VUS тестовых пользователей (по одному на VU, чтобы не упереться в rate-limit по username)..."
for v in $(seq 1 "$MAX_VUS"); do
  curl -s -X POST "$BASE_URL/api/users" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"loadtest-vu-$v\",\"password\":\"$VU_PASSWORD\",\"role\":\"ROLE_ADMIN\"}" > /dev/null
  echo "  loadtest-vu-$v"
done

echo "Создаю категорию $CATEGORY (если ещё нет)..."
curl -s -X POST "$BASE_URL/api/categories" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"$CATEGORY\"}" > /dev/null

echo "Создаю $ITEM_COUNT товаров с начальным приходом..."
EXPIRY=$(date -u -d "+30 days" +"%Y-%m-%dT%H:%M:%S")
CREATED=0

for i in $(seq 1 "$ITEM_COUNT"); do
  RESP=$(curl -s -X POST "$BASE_URL/api/items" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"sku\":\"SKU-LOAD-$i\",\"name\":\"Load Test Item $i\",\"category\":\"$CATEGORY\",\"minStock\":0,\"price\":100.00,\"cost\":50.00}")

  ITEM_ID=$(echo "$RESP" | grep -o '"id":[0-9]*' | head -1 | cut -d':' -f2)

  if [ -z "$ITEM_ID" ]; then
    echo "  [$i] пропущен (вероятно, SKU уже существует)"
    continue
  fi

  curl -s -X POST "$BASE_URL/api/movements/receive" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"itemId\":$ITEM_ID,\"quantity\":100,\"expiryDate\":\"$EXPIRY\"}" > /dev/null

  CREATED=$((CREATED + 1))
  echo "  [$i] item id=$ITEM_ID создан, приход 100 шт."
done

echo "Готово. Создано новых товаров: $CREATED из $ITEM_COUNT."
