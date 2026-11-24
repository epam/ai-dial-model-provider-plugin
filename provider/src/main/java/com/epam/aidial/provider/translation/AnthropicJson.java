package com.epam.aidial.provider.translation;

import com.anthropic.core.JsonField;
import com.anthropic.core.JsonMissing;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.beta.messages.BetaContentBlockParam;
import com.anthropic.models.beta.messages.BetaToolUnion;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.epam.aidial.provider.api.InvalidRequestException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.List;
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
     * The tool's {@code type}, as sent. The model classifies a tool of a type it does not know as a custom tool; a
     * custom tool may leave the type out, and is then {@code custom}.
     */
    public static String toolType(BetaToolUnion tool) {
        return type(raw(tool)).orElse("custom");
    }

    /** The tool as it was sent, to forward it unchanged. */
    public static JsonValue raw(BetaToolUnion tool) {
        return tool._json().orElseThrow(() -> new InvalidRequestException("tools: not an object"));
    }

    /** The content block as it was sent: the only access to a block of a type the model has no variant for. */
    public static JsonValue raw(BetaContentBlockParam block) {
        return block._json().orElseThrow(() -> new InvalidRequestException("content: block is not an object"));
    }

    /** The type of a content block as sent: of a block of a type the model has no variant for too. */
    public static String blockType(BetaContentBlockParam block) {
        return type(raw(block)).orElse("(none)");
    }

    /** What a JSON value is, as the SDK reads it: the helpers keep the types its raw accessors lose. */
    public static Optional<String> string(JsonValue value) {
        return field(value).asString();
    }

    public static Optional<Boolean> bool(JsonValue value) {
        return field(value).asBoolean();
    }

    /** A model of the SDK as the JSON value it is written as, to read it as sent. */
    public static JsonValue rawModel(Object model) {
        return MAPPER.convertValue(model, JsonValue.class);
    }

    /** A JSON value as plain maps, lists and scalars, in the order it was sent. */
    public static Map<String, Object> toMap(JsonValue value) {
        Map<String, Object> map = value.convert(new TypeReference<Map<String, Object>>() {});
        return map == null ? Map.of() : map;
    }

    public static Optional<List<JsonValue>> array(JsonValue value) {
        return field(value).asArray();
    }

    public static Optional<Map<String, JsonValue>> object(JsonValue value) {
        return field(value).asObject();
    }

    /** The {@code type} of a block or tool as sent, if any. */
    public static Optional<String> type(JsonValue raw) {
        return field(raw)
                .asObject()
                .flatMap(o -> Optional.ofNullable(o.get("type")))
                .flatMap(type -> field(type).asString());
    }

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
