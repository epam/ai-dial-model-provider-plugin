package com.epam.aidial.provider.credentials;

import io.vertx.core.Future;

@FunctionalInterface
public interface GoogleTokenSource {

    Future<String> token(Future<Void> aborted);
}
