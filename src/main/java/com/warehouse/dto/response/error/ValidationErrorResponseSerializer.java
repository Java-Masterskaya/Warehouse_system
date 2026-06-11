package com.warehouse.dto.response.error;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

public class ValidationErrorResponseSerializer extends JsonSerializer<ValidationErrorResponse> {

    @Override
    public void serialize(ValidationErrorResponse value,
                          JsonGenerator gen,
                          SerializerProvider serializers) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("error", value.error());
        gen.writeObjectFieldStart("fields");
        for (FieldError fieldError : value.fields()) {
            gen.writeStringField(fieldError.field(), fieldError.message());
        }
        gen.writeEndObject();
        gen.writeEndObject();
    }
}
