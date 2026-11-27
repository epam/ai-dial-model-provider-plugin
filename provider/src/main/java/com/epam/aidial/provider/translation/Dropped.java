package com.epam.aidial.provider.translation;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;

/** What a translation leaves out of a request, named once at its end: one warning however many items there are. */
final class Dropped {

    private final List<String> items = new ArrayList<>();

    void add(String item) {
        if (!items.contains(item)) {
            items.add(item);
        }
    }

    void warn(Logger log) {
        if (!items.isEmpty()) {
            log.warn("Dropped from the Messages API request, which the Responses API has no equivalent of: {}", items);
        }
    }
}
