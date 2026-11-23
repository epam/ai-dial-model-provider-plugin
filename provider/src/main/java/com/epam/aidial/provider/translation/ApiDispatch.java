package com.epam.aidial.provider.translation;

import com.epam.aidial.provider.api.ModelProvider;
import com.epam.aidial.provider.core.Routes;
import io.vertx.core.http.HttpMethod;
import java.util.List;

/**
 * The Messages API routes of a mount whose upstream may speak another API. The upstream decides, per request, what the
 * route does: a Messages upstream is served by the routes as they are (the passthrough), a Responses upstream by
 * {@code responses}.
 */
public final class ApiDispatch implements Routes.Group {

    private final List<String> paths;
    private final Routes messages;
    private final ModelProvider responses;

    /**
     * @param paths the paths ({@code POST}) of {@code messages} to dispatch
     * @param messages the passthrough
     * @param responses what serves a request whose upstream is a Responses API
     */
    public ApiDispatch(List<String> paths, Routes.Group messages, ModelProvider responses) {
        this.paths = List.copyOf(paths);
        this.messages = Routes.of(messages);
        this.responses = responses;
    }

    @Override
    public void register(Routes routes) {
        for (String path : paths) {
            routes.add(
                    HttpMethod.POST,
                    path,
                    request -> UpstreamApi.of(request) == UpstreamApi.RESPONSES
                            ? responses.handle(request)
                            : messages.handle(request));
        }
    }
}
