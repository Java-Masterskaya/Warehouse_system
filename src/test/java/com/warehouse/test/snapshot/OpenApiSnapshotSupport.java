package com.warehouse.test.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Поддержка golden-file тестирования OpenAPI-контракта.
 *
 * <p>Контракт (сгенерированная code-first спека) фиксируется в репозитории как файл
 * {@code src/test/resources/openapi/warehouse.json}. Тест {@code OpenApiContractDriftTest}
 * генерирует спеку заново на каждом запуске и сверяет её с этим файлом: любое несовместимое
 * (или совместимое, но незамеченное) изменение API роняет тест.
 *
 * <p>Сравнение выполняется по разобранному {@link JsonNode}-дереву, а не по сырой строке —
 * порядок ключей JSON-объекта не должен считаться частью контракта.
 */
public final class OpenApiSnapshotSupport {

    /** Эндпоинт springdoc, отдающий сгенерированную спеку. */
    public static final String OPENAPI_DOCS_PATH = "/v3/api-docs";

    /** Путь к зафиксированному контракту на classpath (используется тестами валидации ответов). */
    public static final String SNAPSHOT_CLASSPATH_RESOURCE = "openapi/warehouse.json";

    /**
     * Системное свойство, включающее перезапись снапшота вместо сравнения.
     * Использование: {@code ./gradlew test --tests "*OpenApiContractDriftTest" -DupdateOpenApiSnapshot=true}.
     */
    public static final String UPDATE_SNAPSHOT_PROPERTY = "updateOpenApiSnapshot";

    /**
     * Системное свойство с абсолютным путём к корню проекта (задаётся в {@code build.gradle}
     * через {@code project.projectDir}). Запись снапшота не должна зависеть от того, что рабочая
     * директория форкнутой тестовой JVM случайно совпадёт с корнем проекта.
     */
    private static final String PROJECT_ROOT_DIR_PROPERTY = "projectRootDir";

    private static final Path SNAPSHOT_SOURCE_FILE = resolveSnapshotSourceFile();

    private static final int MAX_REPORTED_DIFFERENCES = 40;

    /** Поля, чьи массивы по JSON Schema являются неупорядоченными наборами. */
    private static final Set<String> UNORDERED_ARRAY_FIELDS = Set.of("oneOf", "anyOf");

    private OpenApiSnapshotSupport() {
    }

    private static Path resolveSnapshotSourceFile() {
        String projectRoot = System.getProperty(PROJECT_ROOT_DIR_PROPERTY);
        Path base;
        if (projectRoot != null) {
            base = Path.of(projectRoot);
        } else {
            base = Path.of("").toAbsolutePath();
        }
        return base.resolve("src").resolve("test").resolve("resources").resolve("openapi").resolve("warehouse.json");
    }

    /**
     * Дёргает {@code GET /v3/api-docs} через переданный {@link MockMvc} и возвращает разобранную спеку.
     *
     * @param mockMvc      MockMvc текущего тестового контекста
     * @param objectMapper ObjectMapper приложения (сохраняет те же настройки сериализации, что и в проде)
     * @return разобранное JSON-дерево сгенерированной спеки
     */
    public static JsonNode fetchLiveSpec(MockMvc mockMvc, ObjectMapper objectMapper) throws Exception {
        String rawSpec = mockMvc.perform(get(OPENAPI_DOCS_PATH))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(rawSpec);
    }

    /**
     * Сверяет свежесгенерированную спеку с зафиксированным в репозитории контрактом.
     *
     * <p>Если задано системное свойство {@value #UPDATE_SNAPSHOT_PROPERTY}, вместо сравнения
     * перезаписывает файл контракта и тест проходит — так сознательно обновляют контракт
     * после намеренного изменения API.
     *
     * @param liveSpec     свежесгенерированная спека (см. {@link #fetchLiveSpec})
     * @param objectMapper ObjectMapper для чтения/записи снапшота
     * @throws AssertionError если контракт разошёлся со снапшотом
     */
    public static void assertNoContractDrift(JsonNode liveSpec, ObjectMapper objectMapper) throws IOException {
        if (Boolean.getBoolean(UPDATE_SNAPSHOT_PROPERTY)) {
            writeSnapshot(liveSpec, objectMapper);
            return;
        }

        JsonNode committedSpec = canonicalize(readCommittedSnapshot(objectMapper));
        JsonNode actualSpec = canonicalize(liveSpec);
        if (committedSpec.equals(actualSpec)) {
            return;
        }

        List<String> differences = new ArrayList<>();
        diff("", committedSpec, actualSpec, differences);
        throw new AssertionError(buildFailureMessage(differences));
    }

    private static JsonNode readCommittedSnapshot(ObjectMapper objectMapper) throws IOException {
        try (InputStream in = OpenApiSnapshotSupport.class.getClassLoader()
                .getResourceAsStream(SNAPSHOT_CLASSPATH_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        "Контракт " + SNAPSHOT_CLASSPATH_RESOURCE + " не найден на classpath. "
                                + "Сгенерируйте его: -D" + UPDATE_SNAPSHOT_PROPERTY + "=true");
            }
            return objectMapper.readTree(in);
        }
    }

    private static void writeSnapshot(JsonNode liveSpec, ObjectMapper objectMapper) throws IOException {
        Files.createDirectories(SNAPSHOT_SOURCE_FILE.getParent());
        String pretty = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(liveSpec);
        Files.writeString(SNAPSHOT_SOURCE_FILE, pretty + System.lineSeparator());
    }

    private static String buildFailureMessage(List<String> differences) {
        StringBuilder message = new StringBuilder(
                "OpenAPI-контракт разошёлся со снапшотом " + SNAPSHOT_SOURCE_FILE + ".\n"
                        + "Если изменение API намеренное, обновите снапшот и закоммитьте его:\n"
                        + "  ./gradlew test --tests \"*OpenApiContractDriftTest\" -D"
                        + UPDATE_SNAPSHOT_PROPERTY + "=true\n"
                        + "Расхождения (" + differences.size() + "):\n");
        differences.stream()
                .limit(MAX_REPORTED_DIFFERENCES)
                .forEach(d -> message.append("  - ").append(d).append('\n'));
        if (differences.size() > MAX_REPORTED_DIFFERENCES) {
            message.append("  ... и ещё ").append(differences.size() - MAX_REPORTED_DIFFERENCES).append('\n');
        }
        return message.toString();
    }

    /**
     * Приводит спеку к каноническому виду, чтобы сравнение не зависело от несущественного порядка.
     *
     * <p>{@code oneOf} и {@code anyOf} — по JSON Schema неупорядоченные наборы альтернатив:
     * springdoc собирает их в порядке, который меняется между прогонами (зависит от того,
     * какие тестовые классы поднимали контекст раньше). Позиционное сравнение таких массивов
     * делало тест нестабильным при зелёном по сути контракте.
     *
     * <p>Остальные массивы ({@code parameters}, {@code enum}, {@code tags}) не сортируются
     * намеренно: там порядок может быть значимым, и его изменение должно ронять тест.
     *
     * @param node узел спеки
     * @return копия узла с отсортированными неупорядоченными массивами
     */
    private static JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            List<String> fieldNames = new ArrayList<>();
            node.fieldNames().forEachRemaining(fieldNames::add);
            Collections.sort(fieldNames);
            for (String fieldName : fieldNames) {
                JsonNode child = canonicalize(node.get(fieldName));
                if (UNORDERED_ARRAY_FIELDS.contains(fieldName) && child.isArray()) {
                    child = sortArray(child);
                }
                result.set(fieldName, child);
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            node.forEach(element -> result.add(canonicalize(element)));
            return result;
        }
        return node;
    }

    private static JsonNode sortArray(JsonNode array) {
        List<JsonNode> elements = new ArrayList<>();
        array.forEach(elements::add);
        elements.sort(Comparator.comparing(JsonNode::toString));
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        elements.forEach(result::add);
        return result;
    }

    private static void diff(String path, JsonNode expected, JsonNode actual, List<String> out) {
        if (expected.equals(actual)) {
            return;
        }
        if (expected.isMissingNode()) {
            out.add("добавлено " + path + ": " + actual);
            return;
        }
        if (actual.isMissingNode()) {
            out.add("удалено " + path + ": " + expected);
            return;
        }
        if (expected.isObject() && actual.isObject()) {
            Set<String> fieldNames = new TreeSet<>();
            expected.fieldNames().forEachRemaining(fieldNames::add);
            actual.fieldNames().forEachRemaining(fieldNames::add);
            for (String fieldName : fieldNames) {
                diff(path + "/" + fieldName, expected.path(fieldName), actual.path(fieldName), out);
            }
            return;
        }
        if (expected.isArray() && actual.isArray()) {
            int size = Math.max(expected.size(), actual.size());
            for (int i = 0; i < size; i++) {
                diff(path + "[" + i + "]", expected.path(i), actual.path(i), out);
            }
            return;
        }
        out.add("изменено " + path + ": было=" + expected + ", стало=" + actual);
    }
}
