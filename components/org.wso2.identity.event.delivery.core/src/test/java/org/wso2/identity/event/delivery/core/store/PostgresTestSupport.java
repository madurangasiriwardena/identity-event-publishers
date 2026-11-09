/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.identity.event.delivery.core.store;

import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.wso2.identity.event.delivery.core.crypto.KeyRing;
import org.wso2.identity.event.delivery.core.crypto.PayloadCipher;
import org.wso2.identity.event.delivery.core.signing.DeliveryHeaders;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

/**
 * Starts one PostgreSQL container for all database tests, applies the real migrations, and creates login users for
 * the two application roles.
 */
public final class PostgresTestSupport {

    public static final String IS_USER = "is_login";
    public static final String WORKER_USER = "worker_login";
    public static final String PASSWORD = "test-only-password";
    public static final String IS_ROLE = "webhook_is";
    public static final String WORKER_ROLE = "webhook_worker";

    private static PostgreSQLContainer<?> container;

    private PostgresTestSupport() {

    }

    public static synchronized void start() throws SQLException {

        if (container != null) {
            return;
        }
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        awaitConnection(postgres);
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("isRole", IS_ROLE);
        placeholders.put("workerRole", WORKER_ROLE);
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration/webhook")
                .placeholders(placeholders)
                .load()
                .migrate();
        container = postgres;
        try (Connection connection = adminDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + IS_USER + " LOGIN PASSWORD '" + PASSWORD + "' IN ROLE " + IS_ROLE);
            statement.execute("CREATE ROLE " + WORKER_USER + " LOGIN PASSWORD '" + PASSWORD + "' IN ROLE "
                    + WORKER_ROLE);
        }
    }

    public static String jdbcUrl() {

        return container.getJdbcUrl();
    }

    public static DataSource adminDataSource() {

        return dataSource(container.getUsername(), container.getPassword());
    }

    public static DataSource isDataSource() {

        return dataSource(IS_USER, PASSWORD);
    }

    public static DataSource workerDataSource() {

        return dataSource(WORKER_USER, PASSWORD);
    }

    public static void truncate() throws SQLException {

        try (Connection connection = adminDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE webhook_delivery, webhook_endpoint_state");
        }
    }

    public static void execute(String sql) throws SQLException {

        try (Connection connection = adminDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    public static PayloadCipher cipher() {

        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 3);
        return new PayloadCipher(new KeyRing(Collections.singletonMap("test-key", key), "test-key"));
    }

    public static NewDelivery.Builder delivery(String webhookId) {

        String eventId = UUID.randomUUID().toString();
        return NewDelivery.builder()
                .eventId(eventId)
                .tenantId("org-1")
                .rootTenantId("root-1")
                .webhookId(webhookId)
                .eventType("https://schemas.identity.wso2.org/events/login/event-type/loginSuccess")
                .topic("myorg.org-1.schema.wso2.v1.event.login")
                .endpointUrl("https://hooks.example.com/" + webhookId)
                .payload(("{\"jti\":\"" + eventId + "\",\"user\":\"José\"}").getBytes(StandardCharsets.UTF_8))
                .headers(DeliveryHeaders.build("https://hub.example.com", "myorg.org-1.schema.wso2.v1.event.login",
                        eventId, "sha256=00"))
                .attemptCount(1)
                .firstRetryDelay(Duration.ZERO)
                .retryWindow(Duration.ofHours(24))
                .lastStatusCode(503)
                .lastError("Service Unavailable");
    }

    /**
     * Waits until the database accepts connections from the host. Some Docker setups, such as Rancher Desktop,
     * forward the mapped port a few seconds after the container reports that it is ready.
     */
    private static void awaitConnection(PostgreSQLContainer<?> postgres) throws SQLException {

        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            try (Connection ignored = dataSource.getConnection()) {
                return;
            } catch (SQLException e) {
                if (System.nanoTime() > deadline) {
                    throw e;
                }
                try {
                    Thread.sleep(250);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private static DataSource dataSource(String user, String password) {

        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(container.getJdbcUrl());
        dataSource.setUser(user);
        dataSource.setPassword(password);
        return dataSource;
    }
}
