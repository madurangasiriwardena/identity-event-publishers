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

package org.wso2.identity.event.delivery.worker;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.wso2.identity.event.delivery.core.http.DeliveryHttpClient;
import org.wso2.identity.event.delivery.core.http.ResponseClassifier;
import org.wso2.identity.event.delivery.core.retry.BackoffPolicy;
import org.wso2.identity.event.delivery.core.security.AddressPolicy;
import org.wso2.identity.event.delivery.core.security.EndpointValidator;
import org.wso2.identity.event.delivery.core.security.GuardedDnsResolver;
import org.wso2.identity.event.delivery.core.store.DeliveryQueue;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * Entry point of the Retry Worker image.
 * <ul>
 *     <li>{@code run} (default): claim and retry deliveries until stopped.</li>
 *     <li>{@code migrate}: apply the schema migrations and exit. The Helm pre-install and pre-upgrade Job runs it with
 *     the schema owner's credentials.</li>
 * </ul>
 */
public final class Main {

    private static final Logger LOG = Logger.getLogger(Main.class.getName());
    static final String MIGRATIONS = "classpath:db/migration/webhook";

    private Main() {

    }

    public static void main(String[] args) throws Exception {

        LogSetup.configure(System.getenv("LOG_LEVEL"));
        WorkerConfig config = WorkerConfig.from(System.getenv());
        String command = args.length == 0 ? "run" : args[0];
        if ("migrate".equals(command)) {
            try (HikariDataSource dataSource = dataSource(config, 2)) {
                migrate(dataSource, config);
            }
            return;
        }
        if (!"run".equals(command)) {
            throw new IllegalArgumentException("Unknown command '" + command + "'. Use 'run' or 'migrate'.");
        }
        Running running = start(config);
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Stopping the Retry Worker.");
            running.stop(config.getShutdownGrace());
            stopped.countDown();
        }, "webhook-shutdown"));
        stopped.await();
    }

    /**
     * Applies the schema migrations.
     *
     * @return Number of migrations applied.
     */
    static int migrate(DataSource dataSource, WorkerConfig config) {

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("isRole", config.getIsRole());
        placeholders.put("workerRole", config.getWorkerRole());
        MigrateResult result = Flyway.configure()
                .dataSource(dataSource)
                .locations(MIGRATIONS)
                .placeholders(placeholders)
                .load()
                .migrate();
        LOG.info("Schema migration finished. Applied " + result.migrationsExecuted + " migrations; schema version "
                + result.targetSchemaVersion + ".");
        return result.migrationsExecuted;
    }

    /**
     * Builds and starts all parts of the worker.
     */
    static Running start(WorkerConfig config) throws IOException {

        HikariDataSource dataSource = dataSource(config, config.getDbPoolSize());
        DeliveryQueue queue = new DeliveryQueue(dataSource);
        AddressPolicy policy = config.isAllowPrivateAddresses() ? AddressPolicy.allowAll()
                : AddressPolicy.publicOnly();
        if (policy.allowsNonPublicAddresses()) {
            LOG.warning("ALLOW_PRIVATE_ADDRESSES is on: webhooks can reach internal addresses. Use it only for "
                    + "local development.");
        }
        DeliveryHttpClient client = new DeliveryHttpClient(new DeliveryHttpClient.Settings(
                config.getConnectTimeoutMillis(), config.getReadTimeoutMillis(),
                config.getConnectionRequestTimeoutMillis(), config.getMaxConnections(),
                config.getMaxConnectionsPerRoute(), config.getIoThreadCount()), new GuardedDnsResolver(policy));
        WorkerMetrics metrics = new WorkerMetrics();
        AtomicBoolean stopping = new AtomicBoolean();
        WebhookGroupSender sender = new WebhookGroupSender(queue, client,
                new EndpointValidator(config.isAllowHttpEndpoints()),
                new ResponseClassifier(config.getRetryableStatusCodes(), config.getPermanentStatusCodes()),
                new BackoffPolicy(config.getBackoffInitial(), config.getBackoffFactor(), config.getBackoffMax(),
                        config.getBackoffJitter()),
                config.getEndpointRejectMinInterval(), config.getWorkerId(), metrics, stopping, Clock.systemUTC());
        RetryWorker worker = new RetryWorker(queue, sender, metrics, new RetryWorker.Settings(config.getWorkerId(),
                config.getBatchSize(), config.getConcurrency(), config.getLease(),
                Duration.ofMillis(config.getReadTimeoutMillis()), config.getBusyPollInterval(),
                config.getIdlePollInterval()), Clock.systemUTC(), stopping);
        Housekeeper housekeeper = new Housekeeper(queue, metrics, new Housekeeper.Settings(
                config.getReaperInterval(), config.getExpiryInterval(), config.getPurgeInterval(),
                config.getStatsInterval(), config.getTerminalRetention(), config.getPurgeBatchSize()));
        AdminServer admin = new AdminServer(config.getHttpPort(), worker::isAlive, worker::isReady,
                metrics::toPrometheusText);
        worker.start();
        housekeeper.start();
        admin.start();
        LOG.info("Retry Worker " + config.getWorkerId() + " started. Batch " + config.getBatchSize()
                + ", concurrency " + config.getConcurrency() + ", lease " + config.getLease().getSeconds() + " s.");
        return new Running(dataSource, client, worker, housekeeper, admin, metrics);
    }

    private static HikariDataSource dataSource(WorkerConfig config, int poolSize) {

        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.getDbUrl());
        hikari.setUsername(config.getDbUser());
        hikari.setPassword(config.getDbPassword());
        hikari.setMaximumPoolSize(poolSize);
        hikari.setPoolName("webhook-delivery");
        return new HikariDataSource(hikari);
    }

    /**
     * The started parts of the worker, stopped in reverse order.
     */
    static final class Running {

        private final HikariDataSource dataSource;
        private final DeliveryHttpClient client;
        private final RetryWorker worker;
        private final Housekeeper housekeeper;
        private final AdminServer admin;
        private final WorkerMetrics metrics;

        Running(HikariDataSource dataSource, DeliveryHttpClient client, RetryWorker worker, Housekeeper housekeeper,
                AdminServer admin, WorkerMetrics metrics) {

            this.dataSource = dataSource;
            this.client = client;
            this.worker = worker;
            this.housekeeper = housekeeper;
            this.admin = admin;
            this.metrics = metrics;
        }

        RetryWorker worker() {

            return worker;
        }

        AdminServer admin() {

            return admin;
        }

        WorkerMetrics metrics() {

            return metrics;
        }

        void stop(Duration grace) {

            worker.stop(grace);
            housekeeper.stop();
            admin.stop();
            try {
                client.close();
            } catch (IOException e) {
                LOG.fine("HTTP client did not close cleanly: " + e.getMessage());
            }
            dataSource.close();
        }
    }
}
