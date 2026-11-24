package com.epam.aidial.provider.translation;

import com.anthropic.core.JsonField;
import com.anthropic.core.JsonMissing;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.beta.messages.BetaSystemMessageParam;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.epam.aidial.provider.api.InvalidRequestException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public final class AnthropicJson {

    private static final JsonMapper MAPPER = ObjectMappers.jsonMapper();
    private static final ObjectReader REQUEST =
            MAPPER.readerFor(MessageCreateParams.Body.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private AnthropicJson() {}

    public static MessageCreateParams.Body parseRequest(byte[] json) {
        try {
            return REQUEST.readValue(json);
        } catch (IOException e) {
            throw new InvalidRequestException("The request body is not valid JSON: " + describe(e));
        }
    }

    /** {@code stream} is not a field of the model: it is one of its additional properties. */
    public static boolean stream(MessageCreateParams.Body request) {
        return optional(additional(request, "stream"), JsonField::asBoolean, "stream: must be a boolean")
                .orElse(false);
    }

    /**
     * A number of the request the model has no field for (the sampling parameters {@code temperature} and {@code
     * top_p} are not in it), as sent; empty if absent or null.
     *
     * @throws InvalidRequestException if it is not a number
     */
    public static Optional<Number> number(MessageCreateParams.Body request, String name) {
        return optional(additional(request, name), JsonField::asNumber, name + ": must be a number");
    }

    /**
     * An optional field of a known type: empty if absent or null.
     *
     * @throws InvalidRequestException if it is of another type
     */
    public static <T> Optional<T> optional(JsonField<T> field, String name) {
        return optional(field, JsonField::asKnown, name + ": has the wrong type");
    }

    /**
     * The model as sent, whatever it is called: empty if absent or null.
     *
     * @throws InvalidRequestException if it is not a string
     */
    public static Optional<String> model(MessageCreateParams.Body request) {
        return optional(
                request._model(),
                model -> model.asKnown().flatMap(m -> m._value().asString()),
                "model: has the wrong type");
    }

    /** The value of a field, read by {@code read}: empty if absent or null, an error if {@code read} cannot. */
    private static <F, T> Optional<T> optional(
            JsonField<F> field, Function<JsonField<F>, Optional<T>> read, String error) {
        if (field.isMissing() || field.isNull()) {
            return Optional.empty();
        }
        return Optional.of(read.apply(field).orElseThrow(() -> new InvalidRequestException(error)));
    }

    /** A property of the request the model has no field for: missing if the request has none. */
    private static JsonField<?> additional(MessageCreateParams.Body request, String name) {
        return field(request._additionalProperties().getOrDefault(name, JsonMissing.of()));
    }

    /**
     * The name of a union's {@code type}, as sent: of a variant the SDK has no constant for too. A union's own {@code
     * type()} reads it from the raw value; {@code (none)} if there is none.
     */
    public static String typeName(JsonField<String> type) {
        return type.asString().orElse("(none)");
    }

    /** The string a constant of the model holds, such as the {@code media_type} of a PDF. */
    public static Optional<String> string(JsonValue value) {
        return field(value).asString();
    }

    /** A model of the SDK as plain maps, lists and scalars, in the order and spelling the API uses. */
    public static Map<String, Object> toMap(Object model) {
        Map<String, Object> map = MAPPER.convertValue(model, new TypeReference<Map<String, Object>>() {});
        return map == null ? Map.of() : map;
    }

    /**
     * The content of a {@code mid_conv_system} block, a block of Core's that the SDK has no variant for: read as the
     * type it is, not as JSON. Empty if the block holds none.
     */
    public static Optional<BetaSystemMessageParam.Content> midConvSystem(Optional<JsonValue> block) {
        return block.map(json -> MAPPER.convertValue(json, MidConvSystem.class))
                .flatMap(midConv -> Optional.ofNullable(midConv.content()));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MidConvSystem(@JsonProperty("content") BetaSystemMessageParam.Content content) {}

    /** {@link JsonValue} extends the raw {@code JsonField}: through this its accessors keep their types. */
    private static JsonField<?> field(JsonValue value) {
        return value;
    }

    private static String describe(IOException e) {
        return e instanceof JsonProcessingException p ? p.getOriginalMessage() : String.valueOf(e.getMessage());
    }

    /**
     * The effort as sent: an effort of a newer API is a value the model's enum has no constant for, but it keeps the
     * string.
     */
    public static Optional<String> effort(MessageCreateParams.Body request) {
        return request.outputConfig().flatMap(c -> c.effort()).map(e -> e.asString());
    }

    /** A message, a stream event or any other model as JSON, in the order and spelling the API uses. */
    public static String write(Object model) {
        try {
            return MAPPER.writeValueAsString(model);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot write " + model.getClass().getSimpleName(), e);
        }
    }
}
