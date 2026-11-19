package com.epam.aidial.provider.credentials;

import io.vertx.core.Future;

/** A failure is an {@link AzureTokens.AzureAdException}. */
public record AzureAd(AzureTokenSource source, String scope) {

    public Future<String> token(Future<Void> aborted) {
        return source.token(scope, aborted);
    }
}
