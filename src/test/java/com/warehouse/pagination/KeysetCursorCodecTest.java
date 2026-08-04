package com.warehouse.pagination;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.warehouse.exception.InvalidCursorException;
import com.warehouse.pagination.KeysetCursorCodec.CursorContext;
import com.warehouse.pagination.KeysetCursorCodec.CursorPosition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link KeysetCursorCodec}.
 */
class KeysetCursorCodecTest {

    private static final String LAST_VALUE = "Dell Latitude";
    private static final long LAST_ID = 42L;
    private static final CursorContext ITEMS_CONTEXT = new CursorContext(
            "items",
            "name",
            "asc",
            List.of("electronics", "dell")
    );

    private ObjectMapper objectMapper;
    private KeysetCursorCodec codec;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        codec = new KeysetCursorCodec(objectMapper);
    }

    @Test
    void encodeAndDecodeRoundTripPreservesContextPosition() {
        String cursor = codec.encode(ITEMS_CONTEXT, LAST_VALUE, LAST_ID);

        CursorPosition position = codec.decode(cursor, ITEMS_CONTEXT);

        assertThat(position.lastValue()).isEqualTo(LAST_VALUE);
        assertThat(position.lastId()).isEqualTo(LAST_ID);
        assertThat(cursor).doesNotContain("+", "/", "=");
    }

    @Test
    void decodeRejectsMalformedBase64() {
        assertThatThrownBy(() -> codec.decode("not-a-valid-base64!", ITEMS_CONTEXT))
                .isInstanceOf(InvalidCursorException.class)
                .hasMessage("Invalid cursor");
    }

    @Test
    void decodeRejectsMalformedJson() {
        String cursor = encodeRaw("not-json");

        assertThatThrownBy(() -> codec.decode(cursor, ITEMS_CONTEXT))
                .isInstanceOf(InvalidCursorException.class)
                .hasMessage("Invalid cursor");
    }

    @Test
    void decodeRejectsJsonNull() {
        String cursor = encodeRaw("null");

        assertThatThrownBy(() -> codec.decode(cursor, ITEMS_CONTEXT))
                .isInstanceOf(InvalidCursorException.class)
                .hasMessage("Invalid cursor");
    }

    @ParameterizedTest
    @MethodSource("mismatchedContexts")
    void decodeRejectsContextMismatch(CursorContext mismatchedContext) {
        String cursor = codec.encode(ITEMS_CONTEXT, LAST_VALUE, LAST_ID);

        assertThatThrownBy(() -> codec.decode(cursor, mismatchedContext))
                .isInstanceOf(InvalidCursorException.class)
                .hasMessage("Invalid cursor");
    }

    @ParameterizedTest
    @MethodSource("invalidDecodedPositions")
    void decodeRejectsInvalidPosition(String lastValue, long lastId) {
        String cursor = cursorWithPosition(lastValue, lastId);

        assertThatThrownBy(() -> codec.decode(cursor, ITEMS_CONTEXT))
                .isInstanceOf(InvalidCursorException.class)
                .hasMessage("Invalid cursor");
    }

    @ParameterizedTest
    @NullAndEmptySource
    void encodeRejectsMissingLastValue(String lastValue) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> codec.encode(ITEMS_CONTEXT, lastValue, LAST_ID))
                .withMessage("Cursor position must contain a value and a positive id");
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void encodeRejectsNonPositiveLastId(long lastId) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> codec.encode(ITEMS_CONTEXT, LAST_VALUE, lastId))
                .withMessage("Cursor position must contain a value and a positive id");
    }

    private static Stream<Arguments> mismatchedContexts() {
        return Stream.of(
                Arguments.of(new CursorContext(
                        "movement-history", "name", "asc", List.of("electronics", "dell"))),
                Arguments.of(new CursorContext(
                        "items", "sku", "asc", List.of("electronics", "dell"))),
                Arguments.of(new CursorContext(
                        "items", "name", "desc", List.of("electronics", "dell"))),
                Arguments.of(new CursorContext(
                        "items", "name", "asc", List.of("hardware", "dell")))
        );
    }

    private static Stream<Arguments> invalidDecodedPositions() {
        return Stream.of(
                Arguments.of(null, LAST_ID),
                Arguments.of("", LAST_ID),
                Arguments.of(LAST_VALUE, 0L),
                Arguments.of(LAST_VALUE, -1L)
        );
    }

    private String cursorWithPosition(String lastValue, long lastId) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("version", 1);
        ObjectNode context = envelope.putObject("context");
        context.put("endpoint", ITEMS_CONTEXT.endpoint());
        context.put("sort", ITEMS_CONTEXT.sort());
        context.put("direction", ITEMS_CONTEXT.direction());
        context.putArray("filters").add(ITEMS_CONTEXT.filters().get(0)).add(ITEMS_CONTEXT.filters().get(1));
        if (lastValue == null) {
            envelope.putNull("lastValue");
        } else {
            envelope.put("lastValue", lastValue);
        }
        envelope.put("lastId", lastId);
        return encodeRaw(envelope.toString());
    }

    private String encodeRaw(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
