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

import org.wso2.identity.event.delivery.core.http.ResponseClassifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Retry Worker settings, read from environment variables. Secrets are read from files, so they can come from a
 * mounted Kubernetes secret instead of the environment.
 */
public final class WorkerConfig {

    private final String dbUrl;
    private final String dbUser;
    private final String dbPassword;
    private final int dbPoolSize;
    private final String workerId;
    private final int batchSize;
    private final int concurrency;
    private final Duration lease;
    private final Duration busyPollInterval;
    private final Duration idlePollInterval;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final int connectionRequestTimeoutMillis;
    private final int maxConnections;
    private final int maxConnectionsPerRoute;
    private final int ioThreadCount;
    private final Duration backoffInitial;
    private final double backoffFactor;
    private final Duration backoffMax;
    private final double backoffJitter;
    private final Duration endpointRejectMinInterval;
    private final Duration terminalRetention;
    private final Duration reaperInterval;
    private final Duration expiryInterval;
    private final Duration purgeInterval;
    private final Duration statsInterval;
    private final int purgeBatchSize;
    private final int httpPort;
    private final boolean allowHttpEndpoints;
    private final boolean allowPrivateAddresses;
    private final Set<Integer> retryableStatusCodes;
    private final Set<Integer> permanentStatusCodes;
    private final Duration shutdownGrace;
    private final String isRole;
    private final String workerRole;

    private WorkerConfig(Map<String, String> env) throws IOException {

        Reader reader = new Reader(env);
        this.dbUrl = reader.required("DB_URL");
        this.dbUser = reader.required("DB_USER");
        this.dbPassword = reader.secret("DB_PASSWORD");
        this.workerId = reader.text("WORKER_ID", reader.text("HOSTNAME", "worker-" + UUID.randomUUID()));
        this.batchSize = reader.positiveInt("BATCH_SIZE", 100);
        this.concurrency = reader.positiveInt("MAX_CONCURRENCY", 32);
        this.dbPoolSize = reader.positiveInt("DB_POOL_SIZE", concurrency + 4);
        this.lease = Duration.ofSeconds(reader.positiveInt("LEASE_SECONDS", 120));
        this.busyPollInterval = Duration.ofMillis(reader.positiveInt("POLL_BUSY_MILLIS", 1000));
        this.idlePollInterval = Duration.ofMillis(reader.positiveInt("POLL_IDLE_MILLIS", 5000));
        this.connectTimeoutMillis = reader.positiveInt("CONNECT_TIMEOUT_MILLIS", 2000);
        this.readTimeoutMillis = reader.positiveInt("READ_TIMEOUT_MILLIS", 10000);
        this.connectionRequestTimeoutMillis = reader.positiveInt("CONNECTION_REQUEST_TIMEOUT_MILLIS", 1000);
        this.maxConnections = reader.positiveInt("MAX_CONNECTIONS", 200);
        this.maxConnectionsPerRoute = reader.positiveInt("MAX_CONNECTIONS_PER_ROUTE", 10);
        this.ioThreadCount = reader.positiveInt("IO_THREADS", 4);
        this.backoffInitial = Duration.ofSeconds(reader.positiveInt("BACKOFF_INITIAL_SECONDS", 30));
        this.backoffFactor = reader.decimal("BACKOFF_FACTOR", 2.0);
        this.backoffMax = Duration.ofSeconds(reader.positiveInt("BACKOFF_MAX_SECONDS", 3600));
        this.backoffJitter = reader.decimal("BACKOFF_JITTER", 0.1);
        this.endpointRejectMinInterval = Duration.ofSeconds(reader.positiveInt("ENDPOINT_REJECT_MIN_SECONDS", 300));
        this.terminalRetention = Duration.ofDays(reader.positiveInt("TERMINAL_RETENTION_DAYS", 7));
        this.reaperInterval = Duration.ofSeconds(reader.positiveInt("REAPER_INTERVAL_SECONDS", 30));
        this.expiryInterval = Duration.ofSeconds(reader.positiveInt("EXPIRY_INTERVAL_SECONDS", 60));
        this.purgeInterval = Duration.ofSeconds(reader.positiveInt("PURGE_INTERVAL_SECONDS", 600));
        this.statsInterval = Duration.ofSeconds(reader.positiveInt("STATS_INTERVAL_SECONDS", 15));
        this.purgeBatchSize = reader.positiveInt("PURGE_BATCH_SIZE", 1000);
        this.httpPort = reader.positiveInt("HTTP_PORT", 8080);
        this.allowHttpEndpoints = Boolean.parseBoolean(reader.text("ALLOW_HTTP_ENDPOINTS", "false"));
        this.allowPrivateAddresses = Boolean.parseBoolean(reader.text("ALLOW_PRIVATE_ADDRESSES", "false"));
        this.retryableStatusCodes = reader.statusCodes("RETRYABLE_STATUS_CODES",
                ResponseClassifier.DEFAULT_RETRYABLE_STATUS_CODES);
        this.permanentStatusCodes = reader.statusCodes("PERMANENT_STATUS_CODES",
                ResponseClassifier.DEFAULT_PERMANENT_STATUS_CODES);
        this.shutdownGrace = Duration.ofSeconds(reader.positiveInt("SHUTDOWN_GRACE_SECONDS", 25));
        this.isRole = reader.text("MIGRATION_IS_ROLE", "webhook_is");
        this.workerRole = reader.text("MIGRATION_WORKER_ROLE", "webhook_worker");
        if (lease.toMillis() <= readTimeoutMillis * 2L) {
            throw new IllegalArgumentException("LEASE_SECONDS must be more than twice READ_TIMEOUT_MILLIS, so a "
                    + "replica can finish an attempt before its lease ends.");
        }
    }

    /**
     * @param env Environment variables.
     * @return Settings.
     * @throws IOException              If a secret file cannot be read.
     * @throws IllegalArgumentException If a value is missing or invalid; the message names the variable.
     */
    public static WorkerConfig from(Map<String, String> env) throws IOException {

        return new WorkerConfig(env);
    }

    public String getDbUrl() {

        return dbUrl;
    }

    public String getDbUser() {

        return dbUser;
    }

    public String getDbPassword() {

        return dbPassword;
    }

    public int getDbPoolSize() {

        return dbPoolSize;
    }

    public String getWorkerId() {

        return workerId;
    }

    public int getBatchSize() {

        return batchSize;
    }

    public int getConcurrency() {

        return concurrency;
    }

    public Duration getLease() {

        return lease;
    }

    public Duration getBusyPollInterval() {

        return busyPollInterval;
    }

    public Duration getIdlePollInterval() {

        return idlePollInterval;
    }

    public int getConnectTimeoutMillis() {

        return connectTimeoutMillis;
    }

    public int getReadTimeoutMillis() {

        return readTimeoutMillis;
    }

    public int getConnectionRequestTimeoutMillis() {

        return connectionRequestTimeoutMillis;
    }

    public int getMaxConnections() {

        return maxConnections;
    }

    public int getMaxConnectionsPerRoute() {

        return maxConnectionsPerRoute;
    }

    public int getIoThreadCount() {

        return ioThreadCount;
    }

    public Duration getBackoffInitial() {

        return backoffInitial;
    }

    public double getBackoffFactor() {

        return backoffFactor;
    }

    public Duration getBackoffMax() {

        return backoffMax;
    }

    public double getBackoffJitter() {

        return backoffJitter;
    }

    public Duration getEndpointRejectMinInterval() {

        return endpointRejectMinInterval;
    }

    public Duration getTerminalRetention() {

        return terminalRetention;
    }

    public Duration getReaperInterval() {

        return reaperInterval;
    }

    public Duration getExpiryInterval() {

        return expiryInterval;
    }

    public Duration getPurgeInterval() {

        return purgeInterval;
    }

    public Duration getStatsInterval() {

        return statsInterval;
    }

    public int getPurgeBatchSize() {

        return purgeBatchSize;
    }

    public int getHttpPort() {

        return httpPort;
    }

    public boolean isAllowHttpEndpoints() {

        return allowHttpEndpoints;
    }

    public boolean isAllowPrivateAddresses() {

        return allowPrivateAddresses;
    }

    public Set<Integer> getRetryableStatusCodes() {

        return Collections.unmodifiableSet(retryableStatusCodes);
    }

    public Set<Integer> getPermanentStatusCodes() {

        return Collections.unmodifiableSet(permanentStatusCodes);
    }

    public Duration getShutdownGrace() {

        return shutdownGrace;
    }

    public String getIsRole() {

        return isRole;
    }

    public String getWorkerRole() {

        return workerRole;
    }

    /**
     * Reads and validates environment variables.
     */
    private static final class Reader {

        private final Map<String, String> env;

        Reader(Map<String, String> env) {

            this.env = env;
        }

        String text(String name, String defaultValue) {

            String value = env.get(name);
            return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
        }

        String required(String name) {

            String value = text(name, null);
            if (value == null) {
                throw new IllegalArgumentException(name + " is required.");
            }
            return value;
        }

        /**
         * Reads NAME_FILE if set, else NAME.
         */
        String secret(String name) throws IOException {

            String file = text(name + "_FILE", null);
            if (file != null) {
                return new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8).trim();
            }
            return required(name);
        }

        int positiveInt(String name, int defaultValue) {

            String value = text(name, null);
            if (value == null) {
                return defaultValue;
            }
            try {
                int parsed = Integer.parseInt(value);
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException e) {
                // Reported below.
            }
            throw new IllegalArgumentException(name + " must be a positive whole number.");
        }

        double decimal(String name, double defaultValue) {

            String value = text(name, null);
            if (value == null) {
                return defaultValue;
            }
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(name + " must be a number.", e);
            }
        }

        Set<Integer> statusCodes(String name, Set<Integer> defaults) {

            String value = text(name, null);
            if (value == null) {
                return defaults;
            }
            Set<Integer> codes = new HashSet<>();
            for (String part : value.split(",")) {
                try {
                    codes.add(Integer.parseInt(part.trim()));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(name + " must be a comma-separated list of status codes.",
                            e);
                }
            }
            return codes;
        }
    }
}
