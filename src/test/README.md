# Тестирование Warehouse System

## Архитектура тестов

### Типы тестов

В проекте используются два типа тестов:

1. **Unit-тесты** — тесты без Spring контекста, используют Mockito для мокирования зависимостей
2. **Integration-тесты** — тесты с полным Spring контекстом и реальными внешними зависимостями (PostgreSQL, Redpanda, Redis)

### Пакеты для тестов

```
src/test/java/com/warehouse/
├── controller/          # Контроллеры
│   └── integration/     # Интеграционные тесты контроллеров (@AutoConfigureMockMvc)
├── service/             # Сервисы
│   └── integration/     # Интеграционные тесты сервисов
├── security/            # Безопасность
│   └── integration/     # Интеграционные тесты безопасности
├── kafka/               # Kafka
│   └── integration/     # Интеграционные тесты Kafka
└── cache/               # Кэширование
    └── integration/     # Интеграционные тесты кэширования (перемещены в service/integration)
```

### Интеграционные тесты

Большинство интеграционных тестов наследуются от `AbstractIntegrationTest`, который управляет жизненным циклом контейнеров:

#### AbstractIntegrationTest

- **PostgreSQL** — база данных через Testcontainers
- **Redpanda** — Kafka-совместимый брокер через Testcontainers  
- **Redis** — кэш через Testcontainers

Контейнеры запускаются **один раз** при загрузке контекста и живут до завершения JVM.

#### Использование

```java
@Tag("integration")
class MyIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    private MyService service;
    
    @Test
    void myIntegrationTest() {
        // Тест с реальной БД, Kafka и Redis
    }
}
```

#### Настройки

- Свойства БД и Kafka настраиваются автоматически через `@DynamicPropertySource`
- application-test.yml содержит только JWT секрет (остальные свойства переопределяются AbstractIntegrationTest)

**Примечание:** Некоторые интеграционные тесты могут использовать `@SpringBootTest` без наследования от `AbstractIntegrationTest`. В таких случаях контейнеры запускаются отдельно для каждого тестового класса (кэширование контекста не работает). Также в `application-test.yml` настроен Testcontainers для PostgreSQL (jdbc:tc:...), что дублирует функциональность AbstractIntegrationTest — по возможности используйте наследование от AbstractIntegrationTest.

### Unit-тесты

Unit-тесты не используют Spring контекст, моки создаются через Mockito:

```java
@ExtendWith(MockitoExtension.class)
class MyServiceTest {
    @Mock
    private MyRepository repository;
    
    @InjectMocks
    private MyService service;
    
    @Test
    void myUnitTest() {
        // Тест с моками
    }
}
```

### Запуск тестов

```bash
# Все тесты через Gradle
./gradlew test
```

```bash
# Все тесты через Makefile
make test
```