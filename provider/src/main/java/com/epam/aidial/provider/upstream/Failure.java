package com.epam.aidial.provider.upstream;

import com.epam.aidial.provider.api.AdapterResponse;
import com.epam.aidial.provider.api.InvalidRequestException;
import io.netty.channel.ConnectTimeoutException;
import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Failure {

    private static final Logger LOG = LoggerFactory.getLogger(Failure.class);

    private Failure() {}

    /** A client that went away gets no answer (the future fails) and no log; an invalid request is not logged. */
    public static Future<AdapterResponse> answer(Throwable e, String path, AdapterErrors errors) {
        return switch (e) {
            // nothing is written: the transport has seen the client go
            case ClientGoneException gone -> Future.failedFuture(gone);
            case InvalidRequestException invalid -> Future.succeededFuture(errors.invalidRequest(invalid.getMessage()));
            case UpstreamRefusedException refused ->
                switch (refused.kind()) {
                    case MISCONFIGURED -> logged(e, path, errors.misconfigured(refused.getMessage()));
                    case BAD_GATEWAY -> logged(e, path, errors.badGateway(refused.getMessage()));
                };
            case TokenUnavailableException ignored -> logged(e, path, errors.tokenUnavailable());
            case CredentialsUnavailableException unavailable ->
                logged(
                        e,
                        path,
                        switch (unavailable.source()) {
                            case SESSION_TAGS, BEDROCK_TOKEN -> errors.plainInternalError();
                            case AWS_ROLE -> errors.awsRoleUnavailable();
                            case AWS_CHAIN -> errors.awsCredentialsUnavailable();
                        });
            case PoolExhaustedException ignored -> logged(e, path, errors.poolExhausted());
            case UpstreamTimeoutException ignored -> logged(e, path, errors.timeout());
            case ConnectTimeoutException ignored -> logged(e, path, errors.timeout());
            // an endpoint that forms no valid request URL is this service's to answer; any other is the connection's
            case IllegalArgumentException ignored -> logged(e, path, errors.internalError());
            default -> logged(e, path, errors.connectionFailed());
        };
    }

    private static Future<AdapterResponse> logged(Throwable cause, String path, AdapterResponse response) {
        String error = cause.getMessage() == null ? cause.toString() : cause.getMessage();
        LOG.atError().addKeyValue("path", path).addKeyValue("error", error).log("request failed");
        return Future.succeededFuture(response);
    }
}
