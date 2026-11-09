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

package org.wso2.identity.event.websub.direct.publisher.config;

import org.wso2.identity.event.delivery.core.http.ResponseClassifier;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Settings of the WebSub direct adapter, read from {@code adapter.websubdirect.*} in
 * identity-outbound-adapter.properties. Times are in milliseconds unless the name says seconds.
 */
public final class WebSubDirectConfiguration {

    static final String ENABLED = "enabled";
    static final String HUB_URL = "hubUrl";
    static final String DATA_SOURCE = "deliveryDataSource";
    static final String CONNECT_TIMEOUT = "connectTimeout";
    static final String READ_TIMEOUT = "readTimeout";
    static final String CONNECTION_REQUEST_TIMEOUT = "connectionRequestTimeout";
    static final String MAX_CONNECTIONS = "maxConnections";
    static final String MAX_CONNECTIONS_PER_ROUTE = "maxConnectionsPerRoute";
    static final String IO_THREAD_COUNT = "ioThreadCount";
    static final String MAX_IN_FLIGHT = "maxInFlight";
    static final String CALLBACK_THREADS = "callbackThreads";
    static final String CALLBACK_QUEUE_CAPACITY = "callbackQueueCapacity";
    static final String STORE_THREADS = "storeThreads";
    static final String STORE_BUFFER_MAX_ENTRIES = "storeBufferMaxEntries";
    static final String STORE_BUFFER_MAX_AGE_SECONDS = "storeBufferMaxAgeSeconds";
    static final String FIRST_RETRY_DELAY_SECONDS = "firstRetryDelaySeconds";
    static final String RETRY_WINDOW_SECONDS = "retryWindowSeconds";
    static final String MAX_PENDING_PER_WEBHOOK = "maxPendingPerWebhook";
    static final String CIRCUIT_FAILURE_THRESHOLD = "circuitFailureThreshold";
    static final String CIRCUIT_OPEN_SECONDS = "circuitOpenSeconds";
    static final String VERIFICATION_TIMEOUT = "verificationTimeout";
    static final String LEASE_SECONDS = "leaseSeconds";
    static final String SHUTDOWN_GRACE_SECONDS = "shutdownGraceSeconds";
    static final String ALLOW_HTTP_ENDPOINTS = "allowHttpEndpoints";
    static final String ALLOW_PRIVATE_ADDRESSES = "allowPrivateAddresses";
    static final String RETRYABLE_STATUS_CODES = "retryableStatusCodes";
    static final String PERMANENT_STATUS_CODES = "permanentStatusCodes";

    private final boolean enabled;
    private final String hubUrl;
    private final String dataSourceName;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final int connectionRequestTimeoutMillis;
    private final int maxConnections;
    private final int maxConnectionsPerRoute;
    private final int ioThreadCount;
    private final int maxInFlight;
    private final int callbackThreads;
    private final int callbackQueueCapacity;
    private final int storeThreads;
    private final int storeBufferMaxEntries;
    private final Duration storeBufferMaxAge;
    private final Duration firstRetryDelay;
    private final Duration retryWindow;
    private final long maxPendingPerWebhook;
    private final int circuitFailureThreshold;
    private final Duration circuitOpenDuration;
    private final int verificationTimeoutMillis;
    private final long leaseSeconds;
    private final Duration shutdownGrace;
    private final boolean allowHttpEndpoints;
    private final boolean allowPrivateAddresses;
    private final Set<Integer> retryableStatusCodes;
    private final Set<Integer> permanentStatusCodes;

    /**
     * @param properties Adapter properties, without the {@code adapter.websubdirect.} prefix.
     * @throws WebSubDirectConfigurationException If the adapter is enabled and a required value is missing, or any
     *                                            value is invalid.
     */
    public WebSubDirectConfiguration(Map<String, String> properties) throws WebSubDirectConfigurationException {

        this.enabled = Boolean.parseBoolean(properties.get(ENABLED));
        this.hubUrl = required(properties, HUB_URL);
        this.dataSourceName = required(properties, DATA_SOURCE);
        this.connectTimeoutMillis = positiveInt(properties, CONNECT_TIMEOUT, 2000);
        this.readTimeoutMillis = positiveInt(properties, READ_TIMEOUT, 5000);
        this.connectionRequestTimeoutMillis = positiveInt(properties, CONNECTION_REQUEST_TIMEOUT, 500);
        this.maxConnections = positiveInt(properties, MAX_CONNECTIONS, 200);
        this.maxConnectionsPerRoute = positiveInt(properties, MAX_CONNECTIONS_PER_ROUTE, 20);
        this.ioThreadCount = positiveInt(properties, IO_THREAD_COUNT, 4);
        this.maxInFlight = positiveInt(properties, MAX_IN_FLIGHT, 500);
        this.callbackThreads = positiveInt(properties, CALLBACK_THREADS, 8);
        this.callbackQueueCapacity = positiveInt(properties, CALLBACK_QUEUE_CAPACITY, 2000);
        this.storeThreads = positiveInt(properties, STORE_THREADS, 4);
        this.storeBufferMaxEntries = positiveInt(properties, STORE_BUFFER_MAX_ENTRIES, 10000);
        this.storeBufferMaxAge = Duration.ofSeconds(positiveInt(properties, STORE_BUFFER_MAX_AGE_SECONDS, 180));
        this.firstRetryDelay = Duration.ofSeconds(positiveInt(properties, FIRST_RETRY_DELAY_SECONDS, 30));
        this.retryWindow = Duration.ofSeconds(positiveInt(properties, RETRY_WINDOW_SECONDS, 86400));
        this.maxPendingPerWebhook = positiveInt(properties, MAX_PENDING_PER_WEBHOOK, 100000);
        this.circuitFailureThreshold = positiveInt(properties, CIRCUIT_FAILURE_THRESHOLD, 5);
        this.circuitOpenDuration = Duration.ofSeconds(positiveInt(properties, CIRCUIT_OPEN_SECONDS, 60));
        this.verificationTimeoutMillis = positiveInt(properties, VERIFICATION_TIMEOUT, 10000);
        this.leaseSeconds = positiveInt(properties, LEASE_SECONDS, 864000);
        this.shutdownGrace = Duration.ofSeconds(positiveInt(properties, SHUTDOWN_GRACE_SECONDS, 20));
        this.allowHttpEndpoints = Boolean.parseBoolean(properties.get(ALLOW_HTTP_ENDPOINTS));
        this.allowPrivateAddresses = Boolean.parseBoolean(properties.get(ALLOW_PRIVATE_ADDRESSES));
        this.retryableStatusCodes = statusCodes(properties, RETRYABLE_STATUS_CODES,
                ResponseClassifier.DEFAULT_RETRYABLE_STATUS_CODES);
        this.permanentStatusCodes = statusCodes(properties, PERMANENT_STATUS_CODES,
                ResponseClassifier.DEFAULT_PERMANENT_STATUS_CODES);
        if (enabled) {
            requirePresent(HUB_URL, hubUrl);
            requirePresent(DATA_SOURCE, dataSourceName);
        }
        if (firstRetryDelay.compareTo(retryWindow) > 0) {
            throw new WebSubDirectConfigurationException(FIRST_RETRY_DELAY_SECONDS + " must not be longer than "
                    + RETRY_WINDOW_SECONDS + ".");
        }
    }

    public boolean isEnabled() {

        return enabled;
    }

    /**
     * @return Hub URL sent in the {@code Link} header. Keep the value the hub used, so receivers see no change.
     */
    public String getHubUrl() {

        return hubUrl;
    }

    /**
     * @return JNDI name of the webhook delivery datasource, for example {@code jdbc/WebhookDeliveryDB}.
     */
    public String getDataSourceName() {

        return dataSourceName;
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

    /**
     * @return Maximum direct requests in progress per node. Above it, events go straight to the database.
     */
    public int getMaxInFlight() {

        return maxInFlight;
    }

    public int getCallbackThreads() {

        return callbackThreads;
    }

    public int getCallbackQueueCapacity() {

        return callbackQueueCapacity;
    }

    public int getStoreThreads() {

        return storeThreads;
    }

    public int getStoreBufferMaxEntries() {

        return storeBufferMaxEntries;
    }

    public Duration getStoreBufferMaxAge() {

        return storeBufferMaxAge;
    }

    public Duration getFirstRetryDelay() {

        return firstRetryDelay;
    }

    public Duration getRetryWindow() {

        return retryWindow;
    }

    public long getMaxPendingPerWebhook() {

        return maxPendingPerWebhook;
    }

    public int getCircuitFailureThreshold() {

        return circuitFailureThreshold;
    }

    public Duration getCircuitOpenDuration() {

        return circuitOpenDuration;
    }

    public int getVerificationTimeoutMillis() {

        return verificationTimeoutMillis;
    }

    public long getLeaseSeconds() {

        return leaseSeconds;
    }

    public Duration getShutdownGrace() {

        return shutdownGrace;
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

    private static String required(Map<String, String> properties, String key) {

        String value = properties.get(key);
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static void requirePresent(String key, String value) throws WebSubDirectConfigurationException {

        if (value == null) {
            throw new WebSubDirectConfigurationException("adapter.websubdirect." + key
                    + " is required when the adapter is enabled.");
        }
    }

    private static int positiveInt(Map<String, String> properties, String key, int defaultValue)
            throws WebSubDirectConfigurationException {

        String value = properties.get(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed <= 0) {
                throw new WebSubDirectConfigurationException("adapter.websubdirect." + key + " must be positive.");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new WebSubDirectConfigurationException("adapter.websubdirect." + key + " must be a number.");
        }
    }

    private static Set<Integer> statusCodes(Map<String, String> properties, String key, Set<Integer> defaults)
            throws WebSubDirectConfigurationException {

        String value = properties.get(key);
        if (value == null || value.trim().isEmpty()) {
            return defaults;
        }
        Set<Integer> codes = new HashSet<>();
        for (String part : value.split(",")) {
            try {
                codes.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException e) {
                throw new WebSubDirectConfigurationException("adapter.websubdirect." + key
                        + " must be a comma-separated list of status codes.");
            }
        }
        return Collections.unmodifiableSet(codes);
    }
}
