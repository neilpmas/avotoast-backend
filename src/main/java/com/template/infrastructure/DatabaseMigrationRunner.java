package com.template.infrastructure;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Runs Flyway migrations <em>after</em> the reactive web server has bound its port,
 * rather than during bean initialisation.
 *
 * <p>Why: this app runs on Fly.io with {@code min_machines_running = 0}. On a cold start
 * after a long idle the machine has no CPU burst balance left and context refresh can
 * take minutes. With the stock {@code FlywayMigrationInitializer} (an
 * {@link org.springframework.beans.factory.InitializingBean}) migrations happen inside
 * {@code ApplicationContext.refresh()}, so port 8080 stays closed for the whole of it and
 * Fly's proxy sees nothing listening -- which both fails the request that woke the
 * machine and makes the machine eligible for Fly's zero-grace-window autostop.
 *
 * <p>{@link ApplicationRunner} beans are invoked by {@code SpringApplication.run()} after
 * {@code refreshContext(...)} has completed, and the web server is started by a
 * {@link org.springframework.context.SmartLifecycle} during {@code finishRefresh()}. So by
 * the time this runner executes the port is already bound. Verified against Spring Boot
 * 4.1.0: {@code SpringApplication.run()} calls {@code refreshContext} then
 * {@code callRunners}, and {@code ReactiveWebServerApplicationContext} registers
 * {@code WebServerStartStopLifecycle} which starts the server during refresh.
 *
 * <p>Failure behaviour is unchanged: throwing from a runner is caught by
 * {@code SpringApplication.handleRunFailure}, which closes the context (stopping the web
 * server, so the port is released again) and rethrows, so the JVM still exits non-zero on
 * a genuinely broken migration. Critically, {@code listeners.ready(...)} -- the only thing
 * that publishes {@code ReadinessState.ACCEPTING_TRAFFIC} -- is never reached, so the app
 * can never be "health green but unmigrated".
 *
 * @see FlywayConfig for the no-op migration strategy that keeps the autoconfigured
 * initializer from doing the work first
 * @see MigrationGateWebFilter for the request-side guard
 */
@Component
public class DatabaseMigrationRunner implements ApplicationRunner, Ordered {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrationRunner.class);

    private final ObjectProvider<Flyway> flywayProvider;
    private final CompletableFuture<Void> completion = new CompletableFuture<>();

    public DatabaseMigrationRunner(ObjectProvider<Flyway> flywayProvider) {
        this.flywayProvider = flywayProvider;
    }

    /**
     * Nothing else should touch the database before the schema is in place, so this runs
     * ahead of any other {@link ApplicationRunner}.
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    // Void has no legal value other than null, so completion.complete(null) below is the
    // only correct way to complete a CompletableFuture<Void> -- not a real null-safety
    // risk, unlike a Mono/Optional that could genuinely be empty. IntelliJ's bundled
    // external annotations mark CompletableFuture#complete's parameter @NotNull as a
    // blanket heuristic with no Void-specific exception, hence the suppression.
    @SuppressWarnings("DataFlowIssue")
    @Override
    public void run(ApplicationArguments args) {
        // Absent when spring.flyway.enabled=false (the integration tests migrate the
        // Testcontainers database themselves). Nothing to wait for, so open the gate.
        Flyway flyway = this.flywayProvider.getIfAvailable();
        if (flyway == null) {
            log.debug("Flyway is not configured; skipping migrations");
            this.completion.complete(null);
            return;
        }

        long startedAt = System.nanoTime();
        log.info("Running Flyway migrations (web server already listening)");
        try {
            MigrateResult result = flyway.migrate();
            Duration took = Duration.ofNanos(System.nanoTime() - startedAt);
            log.info("Flyway migrations complete: {} applied, schema now at version {} ({} ms)",
                result.migrationsExecuted, result.targetSchemaVersion, took.toMillis());
            this.completion.complete(null);
        }
        catch (RuntimeException | Error ex) {
            // Unblock anyone parked on the gate with a failure before the context is torn
            // down, so in-flight requests get a 503 rather than a dropped connection.
            this.completion.completeExceptionally(ex);
            throw ex;
        }
    }

    /**
     * {@code true} once migrations have finished successfully. {@code false} while they
     * are still running <em>and</em> if they failed.
     */
    public boolean isComplete() {
        return this.completion.isDone() && !this.completion.isCompletedExceptionally();
    }

    /**
     * Completes when migrations have finished successfully, or errors if they failed.
     * Subscribing never mutates the shared completion state -- cancellation is suppressed
     * so an abandoned request cannot cancel it for everyone else.
     */
    public Mono<Void> awaitComplete() {
        return Mono.fromFuture(() -> this.completion, true);
    }
}
