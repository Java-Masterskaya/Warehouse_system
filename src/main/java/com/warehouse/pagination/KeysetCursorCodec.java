package com.warehouse.pagination;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.exception.InvalidCursorException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Encodes and validates versioned URL-safe keyset cursors.
 */
@Component
@RequiredArgsConstructor
public class KeysetCursorCodec {

    private static final int VERSION = 1;
    private static final int MAX_CURSOR_LENGTH = 4096;
    private static final String FILTER_HASH_ALGORITHM = "SHA-256";

    private final ObjectMapper objectMapper;

    /**
     * Encodes a cursor position for a request context.
     *
     * @param context normalized request context
     * @param lastValue last sort value
     * @param lastId last entity identifier
     * @return opaque URL-safe cursor
     */
    public String encode(CursorContext context, String lastValue, long lastId) {
        Objects.requireNonNull(context, "context");
        if (lastValue == null || lastValue.isEmpty() || lastId <= 0) {
            throw new IllegalArgumentException("Cursor position must contain a value and a positive id");
        }

        try {
            byte[] payload = objectMapper.writeValueAsBytes(
                    new CursorEnvelope(VERSION, context, lastValue, lastId)
            );
            return Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode cursor", exception);
        }
    }

    /**
     * Decodes a cursor and verifies that it belongs to the current request.
     *
     * @param cursor encoded cursor
     * @param expectedContext normalized current request context
     * @return decoded keyset position
     * @throws InvalidCursorException when the cursor is malformed or foreign
     */
    public CursorPosition decode(String cursor, CursorContext expectedContext) {
        if (cursor == null || cursor.isEmpty() || cursor.length() > MAX_CURSOR_LENGTH) {
            throw new InvalidCursorException();
        }

        CursorEnvelope envelope;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor.getBytes(StandardCharsets.US_ASCII));
            envelope = objectMapper.readValue(decoded, CursorEnvelope.class);
        } catch (IOException | RuntimeException exception) {
            throw new InvalidCursorException(exception);
        }

        if (isInvalid(envelope, expectedContext)) {
            throw new InvalidCursorException();
        }

        return new CursorPosition(envelope.lastValue(), envelope.lastId());
    }

    /**
     * Produces a fixed-size value for binding a cursor to an optional filter.
     *
     * @param value normalized filter value
     * @return URL-safe filter fingerprint, or an empty value when the filter is absent
     */
    public static String fingerprint(String value) {
        if (value == null) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(FILTER_HASH_ALGORITHM);
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to fingerprint cursor filter", exception);
        }
    }

    private boolean isInvalid(CursorEnvelope envelope, CursorContext expectedContext) {
        return envelope == null
                || envelope.version() != VERSION
                || !Objects.equals(envelope.context(), expectedContext)
                || envelope.lastValue() == null
                || envelope.lastValue().isEmpty()
                || envelope.lastId() <= 0;
    }

    /**
     * Identifies the endpoint, ordering and filters that own a cursor.
     *
     * @param endpoint endpoint identifier
     * @param sort sort field
     * @param direction normalized direction
     * @param filters normalized filter values
     */
    public record CursorContext(String endpoint, String sort, String direction, List<String> filters) {

        public CursorContext {
            Objects.requireNonNull(endpoint, "endpoint");
            Objects.requireNonNull(sort, "sort");
            Objects.requireNonNull(direction, "direction");
            filters = List.copyOf(Objects.requireNonNull(filters, "filters"));
        }
    }

    /**
     * Decoded position used by a keyset predicate.
     *
     * @param lastValue last sort value
     * @param lastId last entity identifier
     */
    public record CursorPosition(String lastValue, long lastId) {
    }

    private record CursorEnvelope(
            int version,
            CursorContext context,
            String lastValue,
            long lastId
    ) {
    }
}
