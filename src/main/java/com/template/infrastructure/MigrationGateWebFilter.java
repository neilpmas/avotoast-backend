package com.template.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Holds Connect RPC requests that arrive before {@link DatabaseMigrationRunner} has
 * finished, so they cannot hit an unmigrated schema.
 *
 * <p>Moving migrations behind the port bind means the very request that woke a
 * scaled-to-zero Fly machine now lands <em>during</em> the migration window rather than
 * after it -- that is the normal case on a cold start, not a rare race. Failing it fast
 * would trade "slow but eventually works" for "fails on every cold start" (the web client
 * only offers a manual Retry button). So the gate parks the request non-blockingly until
 * migrations finish and then lets it through, and only gives up -- with a retryable
 * Connect {@code unavailable} / HTTP 503 -- if migrations fail or the wait times out.
 *
 * <p>Scope is deliberately just the Connect path prefix: {@code /actuator/health} and
 * {@code /health} must stay answerable throughout, since answering them at all is the
 * point of the change.
 *
 * <p>Not covered: the {@code net.devh} gRPC server on port 9090. It is bound to
 * {@code 127.0.0.1} and nothing calls it -- the BFF talks Connect over HTTP, and
 * {@code ConnectFilter} dispatches to {@code @GrpcService} beans directly rather than
 * through that server. (A {@code ServerInterceptor} would not help either: this version of
 * connectrpc-spring-boot-starter builds {@code ConnectServiceRegistry} with the
 * constructor that skips {@code @GlobalConnectInterceptor} discovery.)
 */
@Component
public class MigrationGateWebFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(MigrationGateWebFilter.class);

    private static final byte[] UNAVAILABLE_BODY = ("""
        {"code":"unavailable","message":"Database migrations have not finished yet; retry shortly."}"""
    ).getBytes(StandardCharsets.UTF_8);

    private final DatabaseMigrationRunner migrations;
    private final String pathPrefix;
    private final Duration waitTimeout;

    public MigrationGateWebFilter(
        DatabaseMigrationRunner migrations,
        @Value("${connect.path-prefix:/connect}") String pathPrefix,
        @Value("${app.migrations.gate-timeout:60s}") Duration waitTimeout) {
        this.migrations = migrations;
        this.pathPrefix = pathPrefix;
        this.waitTimeout = waitTimeout;
    }

    /**
     * After Spring Security's {@code WebFilterChainProxy} (order -100), so unauthenticated
     * callers still get a 401 rather than learning about our boot state, and before
     * {@code ConnectFilter} (unordered, i.e. lowest precedence).
     */
    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // Steady state: one volatile read, no reactive machinery.
        if (this.migrations.isComplete() || !isGuarded(exchange)) {
            return chain.filter(exchange);
        }
        return this.migrations.awaitComplete()
            .timeout(this.waitTimeout)
            .thenReturn(Boolean.TRUE)
            // Scoped to the wait only -- errors from chain.filter() below must not be
            // rewritten into a 503.
            .onErrorResume(ex -> {
                log.warn("Refusing {} {}: migrations have not completed ({})",
                    exchange.getRequest().getMethod(), exchange.getRequest().getPath().value(),
                    ex.toString());
                return Mono.just(Boolean.FALSE);
            })
            .flatMap(migrated -> migrated ? chain.filter(exchange) : writeUnavailable(exchange));
    }

    private boolean isGuarded(ServerWebExchange exchange) {
        return exchange.getRequest().getPath().value().startsWith(this.pathPrefix);
    }

    private Mono<Void> writeUnavailable(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().add(HttpHeaders.RETRY_AFTER, "5");
        DataBuffer body = response.bufferFactory().wrap(UNAVAILABLE_BODY);
        return response.writeWith(Mono.just(body));
    }
}
