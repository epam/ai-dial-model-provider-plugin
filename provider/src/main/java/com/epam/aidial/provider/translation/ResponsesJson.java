package com.epam.aidial.provider.translation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.openai.core.JsonField;
import com.openai.core.JsonValue;
import com.openai.core.ObjectMappers;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;
import java.io.IOException;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The OpenAI SDK's models behind one small door, as {@link AnthropicJson} is the Anthropic SDK's. What the upstream
 * answers is read here; a body that is not JSON is an {@link UpstreamResponseException}. As there, parsing is lazy, and
 * a getter that finds a field missing or mistyped throws {@code OpenAIInvalidDataException}.
 */
public final class ResponsesJson {

    private static final JsonMapper MAPPER = ObjectMappers.jsonMapper();
    // Data after the value is an error, as the mapper alone does not take it
    private static final ObjectReader RESPONSE =
            MAPPER.readerFor(Response.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final ObjectReader EVENT =
            MAPPER.readerFor(ResponseStreamEvent.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private ResponsesJson() {}

    public static Response parseResponse(byte[] json) {
        try {
            return RESPONSE.readValue(json);
        } catch (IOException e) {
            throw unreadable(Response.class, e);
        }
    }

    /**
     * One event of the stream (the data of an SSE event). An event of a type the SDK does not know has no typed form,
     * only {@code _json()}; so has one that is mistyped, until a getter throws.
     */
    public static ResponseStreamEvent parseEvent(String json) {
        try {
            return EVENT.readValue(json);
        } catch (IOException e) {
            throw unreadable(ResponseStreamEvent.class, e);
        }
    }

    /**
     * The {@code type} as sent of an item, part or event that the SDK has no variant for, which is given as raw JSON;
     * {@code (none)} if there is none, as for {@link AnthropicJson#typeName}.
     */
    public static String unknownType(@Nullable JsonValue json) {
        return Optional.ofNullable(json)
                .flatMap(value -> field(value).asObject())
                .flatMap(object -> Optional.ofNullable(object.get("type")))
                .flatMap(type -> field(type).asString())
                .orElse("(none)");
    }

    /** {@link JsonValue} extends the raw {@code JsonField}: through this its accessors keep their types. */
    private static JsonField<?> field(JsonValue value) {
        return value;
    }

    private static UpstreamResponseException unreadable(Class<?> type, IOException e) {
        return new UpstreamResponseException("not a " + type.getSimpleName() + ": " + SdkJson.describe(e), e);
    }

    /**
     * The request's JSON, with {@code stream}, which the model does not have as a field, set as an additional property.
     */
    public static String requestJson(ResponseCreateParams.Builder request, boolean stream) {
        request.putAdditionalBodyProperty("stream", JsonValue.from(stream));
        return write(request.build()._body());
    }

    public static String write(Object model) {
        return SdkJson.write(MAPPER, model);
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
