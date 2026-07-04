#!/bin/sh

# Определяем адрес Consul
if [ -n "$CONSUL_HOST" ]; then
    CONSUL_ADDR="http://${CONSUL_HOST}:8500"
elif [ -f /.dockerenv ]; then
    CONSUL_ADDR="http://consul:8500"
else
    CONSUL_ADDR="http://localhost:8500"
fi

echo "⏳ Using Consul at: $CONSUL_ADDR"
echo "⏳ Waiting for Consul to be ready..."

# Ждем, пока Consul станет доступен
while ! curl -s ${CONSUL_ADDR}/v1/status/leader | grep -q ":"; do
    echo "Waiting for Consul leader..."
    sleep 2
done

echo "✅ Consul is ready. Seeding KV..."

# Загружаем конфигурацию
curl -X PUT --data-binary @/warehouse-config.yaml \
     ${CONSUL_ADDR}/v1/kv/config/warehouse-system/data

echo "✅ Seeding complete!"