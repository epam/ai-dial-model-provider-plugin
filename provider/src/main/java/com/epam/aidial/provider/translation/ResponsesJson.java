package com.epam.aidial.provider.translation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.openai.core.JsonField;
import com.openai.core.JsonValue;
import com.openai.core.ObjectMappers;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;
import java.io.IOException;
import java.util.Optional;

/**
 * The OpenAI SDK's models behind one small door, as {@link AnthropicJson} is the Anthropic SDK's. What the upstream
 * answers is read here; a body that is not JSON is an {@link UpstreamResponseException}. As there, parsing is lazy, and
 * a getter that finds a field missing or mistyped throws {@code OpenAIInvalidDataException}.
 */
public final class ResponsesJson {

    private static final JsonMapper MAPPER = ObjectMappers.jsonMapper();

    private ResponsesJson() {}

    /** The upstream's answer, whole. */
    public static Response parseResponse(byte[] json) {
        return read(json, Response.class);
    }

    /**
     * One event of the stream (the data of an SSE event). An event of a type the SDK does not know has no typed form,
     * only {@code _json()}; so has one that is mistyped, until a getter throws.
     */
    public static ResponseStreamEvent parseEvent(byte[] json) {
        return read(json, ResponseStreamEvent.class);
    }

    /**
     * A counter: 0 if absent or null.
     *
     * @throws OpenAIInvalidDataException if it is not a number
     */
    public static long count(JsonField<Long> field, String name) {
        if (field.isMissing() || field.isNull()) {
            return 0;
        }
        return field.asKnown().orElseThrow(() -> new OpenAIInvalidDataException(name + " is not a number"));
    }

    /** The {@code type} of an output item or content part as sent, if it has one. */
    public static Optional<String> type(Optional<JsonValue> raw) {
        return raw.flatMap(value -> field(value).asObject())
                .flatMap(object -> Optional.ofNullable(object.get("type")))
                .flatMap(type -> field(type).asString());
    }

    /**
     * The {@code type} as sent.
     *
     * @throws OpenAIInvalidDataException if there is none
     */
    public static String requiredType(Optional<JsonValue> raw, String what) {
        return type(raw).orElseThrow(() -> new OpenAIInvalidDataException(what + " has no type"));
    }

    /**
     * A nested object: empty if absent or null.
     *
     * @throws OpenAIInvalidDataException if it is not an object of the type
     */
    public static <T> Optional<T> object(JsonField<T> field, String name) {
        if (field.isMissing() || field.isNull()) {
            return Optional.empty();
        }
        return Optional.of(
                field.asKnown().orElseThrow(() -> new OpenAIInvalidDataException(name + " is not an object")));
    }

    /** {@link JsonValue} extends the raw {@code JsonField}: through this its accessors keep their types. */
    private static JsonField<?> field(JsonValue value) {
        return value;
    }

    /** Data after the value is an error, as the mapper alone does not take it. */
    private static <T> T read(byte[] json, Class<T> type) {
        try {
            return MAPPER.readerFor(type)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(json);
        } catch (IOException e) {
            throw new UpstreamResponseException("not a " + type.getSimpleName() + ": " + describe(e), e);
        }
    }

    private static String describe(IOException e) {
        return e instanceof JsonProcessingException p ? p.getOriginalMessage() : e.toString();
    }

    /**
     * The request's JSON, with {@code stream}, which the model does not have as a field, set as an additional property.
     */
    public static String requestJson(ResponseCreateParams.Builder request, boolean stream) {
        request.putAdditionalBodyProperty("stream", JsonValue.from(stream));
        return write(request.build()._body());
    }

    /**
     * A request as it was written by {@link #requestJson}.
     *
     * @throws IllegalArgumentException if it is not a Responses request
     */
    public static ResponseCreateParams.Body parseRequest(byte[] json) {
        try {
            return MAPPER.readValue(json, ResponseCreateParams.Body.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("not a Responses request: " + describe(e), e);
        }
    }

    /** Whether the request asks for a stream: {@code stream} is one of its additional properties. */
    public static boolean streams(ResponseCreateParams.Body request) {
        return JsonValue.from(true)
                .equals(ResponseCreateParams.builder()
                        .body(request)
                        .build()
                        ._additionalBodyProperties()
                        .get("stream"));
    }

    /** A model of the SDK as JSON. */
    public static String write(Object model) {
        try {
            return MAPPER.writeValueAsString(model);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot write " + model.getClass().getSimpleName(), e);
        }
    }

    /**
     * A model of the Anthropic SDK as the model of this SDK that says the same, where the two have the same JSON and
     * only free-form members, such as a JSON schema, have no type of their own.
     */
    public static <T> T fromAnthropic(Object model, Class<T> type) {
        try {
            return MAPPER.readValue(AnthropicJson.write(model), type);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + type.getSimpleName() + " from " + model, e);
        }
    }
}
