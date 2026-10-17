package com.template.infrastructure;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MigrationGateWebFilterTest {

    // run(ApplicationArguments) never reads its argument -- this stands in for a real one
    // rather than null, now that the package is @NullMarked.
    private static final ApplicationArguments NO_ARGS = new DefaultApplicationArguments();

    @SuppressWarnings("unchecked")
    private static ObjectProvider<Flyway> provider(Flyway flyway) {
        ObjectProvider<Flyway> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(flyway);
        return provider;
    }

    /** A runner whose migrations succeed, but only once {@code run(NO_ARGS)} is called. */
    private static DatabaseMigrationRunner pendingRunner() {
        Flyway flyway = mock(Flyway.class);
        when(flyway.migrate()).thenReturn(new MigrateResult("13", "db", "app", "postgresql"));
        return new DatabaseMigrationRunner(provider(flyway));
    }

    private static DatabaseMigrationRunner completedRunner() {
        DatabaseMigrationRunner runner = pendingRunner();
        runner.run(NO_ARGS);
        return runner;
    }

    private static MigrationGateWebFilter gate(DatabaseMigrationRunner runner, Duration timeout) {
        return new MigrationGateWebFilter(runner, "/connect", timeout);
    }

    private static MockServerWebExchange connectRequest() {
        return MockServerWebExchange.from(
            MockServerHttpRequest.post("/connect/template.v1.TemplateService/GetServerInfo"));
    }

    private record RecordingChain(AtomicBoolean called) implements WebFilterChain {
        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            return Mono.fromRunnable(() -> this.called.set(true));
        }
    }

    @Test
    void passesThroughOnceMigrationsAreDone() {
        AtomicBoolean reachedHandler = new AtomicBoolean();
        MockServerWebExchange exchange = connectRequest();

        StepVerifier.create(gate(completedRunner(), Duration.ofSeconds(5))
                .filter(exchange, new RecordingChain(reachedHandler)))
            .verifyComplete();

        assertThat(reachedHandler).isTrue();
    }

    @Test
    void letsHealthChecksThroughWhileMigrationsAreStillRunning() {
        AtomicBoolean reachedHandler = new AtomicBoolean();
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/actuator/health"));

        StepVerifier.create(gate(pendingRunner(), Duration.ofSeconds(5))
                .filter(exchange, new RecordingChain(reachedHandler)))
            .verifyComplete();

        assertThat(reachedHandler).as("the health endpoint must never be gated").isTrue();
    }

    @Test
    void parksAConnectRequestUntilMigrationsFinishAndThenLetsItThrough() {
        DatabaseMigrationRunner runner = pendingRunner();
        AtomicBoolean reachedHandler = new AtomicBoolean();
        MockServerWebExchange exchange = connectRequest();

        Mono<Void> inFlight = gate(runner, Duration.ofSeconds(10))
            .filter(exchange, new RecordingChain(reachedHandler));

        // Void's only legal value is null, so Mono<Void>.toFuture() honestly returns
        // CompletableFuture<@Nullable Void> -- matching that here rather than the
        // @NullMarked default of a non-null type argument.
        CompletableFuture<@Nullable Void> subscribed = inFlight.toFuture();
        assertThat(reachedHandler).as("must not reach the handler before migrations finish").isFalse();

        runner.run(NO_ARGS);

        subscribed.orTimeout(10, java.util.concurrent.TimeUnit.SECONDS).join();
        assertThat(reachedHandler).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void returnsRetryableConnectUnavailableWhenMigrationsFail() {
        Flyway flyway = mock(Flyway.class);
        when(flyway.migrate()).thenThrow(new IllegalStateException("bad migration script"));
        DatabaseMigrationRunner runner = new DatabaseMigrationRunner(provider(flyway));
        AtomicBoolean reachedHandler = new AtomicBoolean();
        MockServerWebExchange exchange = connectRequest();

        CompletableFuture<@Nullable Void> inFlight = gate(runner, Duration.ofSeconds(10))
            .filter(exchange, new RecordingChain(reachedHandler))
            .toFuture();

        assertThatMigrationFails(runner);
        inFlight.orTimeout(10, java.util.concurrent.TimeUnit.SECONDS).join();

        assertThat(reachedHandler).as("must never reach an unmigrated schema").isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
        assertThat(exchange.getResponse().getBodyAsString().block())
            .contains("\"code\":\"unavailable\"");
    }

    @Test
    void givesUpWithUnavailableWhenTheWaitTimesOut() {
        AtomicBoolean reachedHandler = new AtomicBoolean();
        MockServerWebExchange exchange = connectRequest();

        StepVerifier.create(gate(pendingRunner(), Duration.ofMillis(50))
                .filter(exchange, new RecordingChain(reachedHandler)))
            .verifyComplete();

        assertThat(reachedHandler).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getBodyAsString().block())
            .isEqualTo("{\"code\":\"unavailable\","
                + "\"message\":\"Database migrations have not finished yet; retry shortly.\"}");
    }

    private static void assertThatMigrationFails(DatabaseMigrationRunner runner) {
        try {
            runner.run(NO_ARGS);
            throw new AssertionError("expected the migration to fail");
        }
        catch (IllegalStateException expected) {
            // the runner rethrows so SpringApplication.run() fails -- see its unit test
        }
    }
}
