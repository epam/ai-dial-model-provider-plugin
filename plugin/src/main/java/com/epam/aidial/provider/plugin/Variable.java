package com.epam.aidial.provider.plugin;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import org.jspecify.annotations.Nullable;

/**
 * A variable of the service's configuration as an entry sets it: the text the environment would hold. A string is
 * itself, a number or a boolean its text, an object its JSON, and {@code null} unsets the variable.
 *
 * <p>A variable may be a credential: it prints as {@code ***}.
 */
@JsonDeserialize(using = Variable.Deserializer.class)
record Variable(@JsonValue @Nullable String text) {

    static final Variable UNSET = new Variable(null);

    @Override
    public String toString() {
        return "***";
    }

    static final class Deserializer extends StdDeserializer<Variable> {

        Deserializer() {
            super(Variable.class);
        }

        @Override
        public Variable deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode value = context.readTree(parser);
            if (value.isArray()) {
                return (Variable) context.handleUnexpectedToken(Variable.class, parser);
            }
            return new Variable(value.isObject() ? value.toString() : value.asText());
        }

        @Override
        public Variable getNullValue(DeserializationContext context) {
            return UNSET;
        }

        /** A variable the entry leaves out is no variable of it, unlike one it sets to null. */
        @Override
        public @Nullable Variable getAbsentValue(DeserializationContext context) {
            return null;
        }
    }
}
