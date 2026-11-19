package com.epam.aidial.provider.credentials;

import io.vertx.core.Future;

/** A failure is an {@link AzureTokens.AzureAdException}. */
@FunctionalInterface
public interface AzureTokenSource {

    Future<String> token(String scope, Future<Void> aborted);
}
