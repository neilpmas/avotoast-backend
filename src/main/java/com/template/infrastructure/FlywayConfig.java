package com.template.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Gives Flyway a JDBC {@link DataSource} of its own.
 *
 * <p>Spring Boot's {@code DataSourceAutoConfiguration} backs off whenever an R2DBC
 * {@code ConnectionFactory} bean is present, on the assumption that a reactive app has no
 * use for JDBC. This app does use R2DBC for its own data access (see {@code spring.r2dbc.*}),
 * but Flyway ships no R2DBC driver -- it can only migrate over JDBC. With no
 * {@code DataSource} bean and no {@code spring.flyway.url}, Flyway's autoconfiguration has
 * nothing to connect with and quietly contributes nothing at all.
 *
 * <p>The failure mode is the dangerous kind: the application starts perfectly, health is
 * green, and no error or warning is logged anywhere -- migrations simply never run. It only
 * surfaces later, as the first query against a table that was never created.
 *
 * <p>So the JDBC side is wired explicitly here, from the same {@code spring.datasource.*}
 * properties the rest of the template's configuration uses, and marked
 * {@link FlywayDataSource} so Flyway picks it in preference to anything else. It is used
 * for migrations only; the application's own reads and writes still go through R2DBC.
 *
 * <p>Note this is a separate connection pool from the R2DBC one, pointed at the same
 * database. It is short-lived in practice -- Flyway opens it during startup and the pool
 * then sits idle -- so no pool sizing is configured beyond the defaults.
 *
 * <p>Gated on {@code spring.flyway.enabled} to match the condition on Boot's own
 * {@code FlywayAutoConfiguration}. Without the gate this would build a {@code DataSource}
 * even for slice tests that switch Flyway off and exclude {@code DataSourceAutoConfiguration}
 * precisely because they need no database, and those contexts would then fail to start on
 * an unresolvable {@code DATABASE_URL}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FlywayConfig {

    @Bean
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties flywayDataSourceProperties() {
        return new DataSourceProperties();
    }

    @FlywayDataSource
    @Bean
    public DataSource flywayDataSource(DataSourceProperties flywayDataSourceProperties) {
        return flywayDataSourceProperties.initializeDataSourceBuilder().build();
    }

    /**
     * Takes the work away from the autoconfigured {@code FlywayMigrationInitializer},
     * which is an {@code InitializingBean} and would therefore migrate during
     * {@code ApplicationContext.refresh()} -- i.e. before the reactive web server binds
     * its port. {@link DatabaseMigrationRunner} runs {@code flyway.migrate()} instead, as
     * an {@code ApplicationRunner}, once the port is open.
     *
     * <p>Keeping the autoconfiguration (rather than {@code spring.flyway.enabled=false}
     * plus a hand-built Flyway) preserves all the {@code spring.flyway.*} binding --
     * schemas, locations, and the {@code connect-retries} that ride out Neon's cold-start
     * wake. The initializer bean still gets created; it just no longer opens a JDBC
     * connection, so nothing in the bean graph blocks on the database.
     *
     * <p>Do not remove this without also removing {@link DatabaseMigrationRunner}: with
     * neither, migrations would never run at all.
     */
    @Bean
    public FlywayMigrationStrategy deferMigrationsUntilAfterWebServerBind() {
        return _ -> {
            // Intentionally empty -- see DatabaseMigrationRunner.
        };
    }
}
