package com.epam.aidial.provider.api;

import io.vertx.core.Future;

/**
 * What the work done for a request needs of it besides its own data: when its client goes, and DIAL Core to call. The
 * work's callbacks keep this, not the {@link AdapterRequest}, whose body they must not keep alive (see {@code Unsent}).
 *
 * @param aborted see {@link AdapterRequest#aborted()}
 * @param core    see {@link AdapterRequest#core()}
 */
public record RequestScope(Future<Void> aborted, Handler core) {}
