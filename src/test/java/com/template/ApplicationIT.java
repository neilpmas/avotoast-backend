package com.template;

import io.sentry.Sentry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ApplicationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /**
     * Deliberately wired through plain properties rather than {@code @ServiceConnection}.
     *
     * <p>{@code @ServiceConnection} contributes a {@code JdbcConnectionDetails} bean, which
     * is enough on its own to give Flyway a JDBC connection -- so it hides the very defect
     * {@link #flywayMigrationsActuallyRan()} exists to catch. Production has no such bean:
     * it gets {@code DATABASE_URL} and {@code R2DBC_URL} from the environment and nothing
     * else. Driving the test the same way keeps the two honest about each other.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("DATABASE_URL", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("R2DBC_URL", () -> "r2dbc:postgresql://" + postgres.getHost()
            + ":" + postgres.getMappedPort(5432) + "/" + postgres.getDatabaseName());
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
    }

    @LocalServerPort
    private int port;

    @Test
    void healthEndpointReturnsOk() {
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .build()
            .get().uri("/health")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.status").isEqualTo("ok");
    }

    /**
     * Proves Flyway actually ran, not merely that the app started.
     *
     * <p>Spring Boot's {@code DataSourceAutoConfiguration} backs off when an R2DBC
     * {@code ConnectionFactory} is present, and Flyway has no R2DBC driver -- so without a
     * {@code @FlywayDataSource} bean migrations silently never execute: no error, no log
     * line, no schema. The only way to tell the difference is to ask the database.
     *
     * <p>Queried over a plain JDBC connection rather than an injected bean, so the
     * assertion is about the real state of the container's database and not about
     * whatever the application context happens to believe.
     */
    @Test
    @SuppressWarnings({"SqlNoDataSourceInspection", "SqlResolve"}) // queries a throwaway Testcontainers DB; no IDE data source exists for it
    void flywayMigrationsActuallyRan() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {

            try (ResultSet rs = statement.executeQuery(
                "select count(*) from information_schema.tables"
                    + " where table_schema = 'app' and table_name = 'flyway_schema_history'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1))
                    .as("Flyway must have created its schema history table in the app schema")
                    .isEqualTo(1);
            }

            try (ResultSet rs = statement.executeQuery(
                "select version, success from app.flyway_schema_history"
                    + " where version = '1'")) {
                assertThat(rs.next())
                    .as("V1__init.sql must be recorded in the schema history")
                    .isTrue();
                assertThat(rs.getBoolean("success"))
                    .as("V1__init.sql must have applied successfully")
                    .isTrue();
            }
        }
    }

    /**
     * Sentry ships on the classpath but must stay inert until a DSN is configured: an unset
     * {@code SENTRY_DSN} is a no-op, not a startup failure and not a silent network call.
     */
    @Test
    void sentryIsInertWithoutADsn() {
        assertThat(Sentry.isEnabled()).isFalse();
    }
}
