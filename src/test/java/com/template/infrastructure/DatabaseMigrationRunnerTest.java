package com.template.infrastructure;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseMigrationRunnerTest {

    // run(ApplicationArguments) never reads its argument -- this stands in for a real one
    // rather than null, now that the package is @NullMarked.
    private static final ApplicationArguments NO_ARGS = new DefaultApplicationArguments();

    // Deliberately accepts null: it simulates ObjectProvider.getIfAvailable() when Flyway
    // autoconfiguration is disabled (spring.flyway.enabled=false), a real production case
    // DatabaseMigrationRunner.run() explicitly handles.
    @SuppressWarnings("unchecked")
    private static ObjectProvider<Flyway> provider(@Nullable Flyway flyway) {
        ObjectProvider<Flyway> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(flyway);
        return provider;
    }

    @Test
    void runsMigrationsAndOpensTheGate() {
        Flyway flyway = mock(Flyway.class);
        MigrateResult result = new MigrateResult("13", "db", "app", "postgresql");
        result.migrationsExecuted = 1;
        when(flyway.migrate()).thenReturn(result);

        DatabaseMigrationRunner runner = new DatabaseMigrationRunner(provider(flyway));
        assertThat(runner.isComplete()).isFalse();

        runner.run(NO_ARGS);

        verify(flyway).migrate();
        assertThat(runner.isComplete()).isTrue();
        StepVerifier.create(runner.awaitComplete()).verifyComplete();
    }

    @Test
    void aFailedMigrationRethrowsSoSpringApplicationRunFails() {
        Flyway flyway = mock(Flyway.class);
        RuntimeException boom = new IllegalStateException("bad migration script");
        when(flyway.migrate()).thenThrow(boom);

        DatabaseMigrationRunner runner = new DatabaseMigrationRunner(provider(flyway));

        assertThatThrownBy(() -> runner.run(NO_ARGS)).isSameAs(boom);

        // Never "complete", and anyone parked on the gate is errored rather than left hanging.
        assertThat(runner.isComplete()).isFalse();
        StepVerifier.create(runner.awaitComplete()).verifyErrorSatisfies(
            ex -> assertThat(ex).hasMessage("bad migration script"));
    }

    @Test
    void opensTheGateImmediatelyWhenFlywayIsDisabled() {
        DatabaseMigrationRunner runner = new DatabaseMigrationRunner(provider(null));

        runner.run(NO_ARGS);

        assertThat(runner.isComplete()).isTrue();
        StepVerifier.create(runner.awaitComplete()).verifyComplete();
    }

    @Test
    void cancellingOneSubscriberDoesNotCancelTheSharedCompletion() {
        Flyway flyway = mock(Flyway.class);
        when(flyway.migrate()).thenReturn(new MigrateResult("13", "db", "app", "postgresql"));
        DatabaseMigrationRunner runner = new DatabaseMigrationRunner(provider(flyway));

        // Subscribe and walk away before migrations finish.
        runner.awaitComplete().subscribe().dispose();

        runner.run(NO_ARGS);

        StepVerifier.create(runner.awaitComplete())
            .expectSubscription()
            .verifyComplete();
        assertThat(runner.awaitComplete().block(Duration.ofSeconds(1))).isNull();
    }
}
