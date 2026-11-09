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

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A failed delivery that Identity Server stores for the Retry Worker. Build it with {@link #builder()}.
 * <p>
 * Times are given as durations from now and applied with the database clock, so clock differences between pods do
 * not matter.
 */
public final class NewDelivery {

    private final String eventId;
    private final String tenantId;
    private final String rootTenantId;
    private final String webhookId;
    private final String eventType;
    private final String topic;
    private final String endpointUrl;
    private final byte[] payload;
    private final Map<String, String> headers;
    private final DeliveryStatus status;
    private final int attemptCount;
    private final Duration firstRetryDelay;
    private final Duration retryWindow;
    private final Integer lastStatusCode;
    private final String lastError;

    private NewDelivery(Builder builder) {

        this.eventId = require(builder.eventId, "eventId", 64);
        this.tenantId = require(builder.tenantId, "tenantId", 64);
        this.rootTenantId = require(builder.rootTenantId, "rootTenantId", 64);
        this.webhookId = require(builder.webhookId, "webhookId", 64);
        this.eventType = require(builder.eventType, "eventType", 255);
        this.topic = require(builder.topic, "topic", 512);
        this.endpointUrl = require(builder.endpointUrl, "endpointUrl", 2048);
        if (builder.payload == null) {
            throw new IllegalArgumentException("payload is required.");
        }
        this.payload = builder.payload.clone();
        if (builder.headers == null) {
            throw new IllegalArgumentException("headers are required.");
        }
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(builder.headers));
        if (builder.status != DeliveryStatus.PENDING && builder.status != DeliveryStatus.FAILED) {
            throw new IllegalArgumentException("A new delivery is either PENDING or FAILED.");
        }
        this.status = builder.status;
        if (builder.attemptCount < 0) {
            throw new IllegalArgumentException("attemptCount must not be negative.");
        }
        this.attemptCount = builder.attemptCount;
        this.firstRetryDelay = requireNonNegative(builder.firstRetryDelay, "firstRetryDelay");
        this.retryWindow = requireNonNegative(builder.retryWindow, "retryWindow");
        this.lastStatusCode = builder.lastStatusCode;
        this.lastError = builder.lastError;
    }

    /**
     * @return A new builder.
     */
    public static Builder builder() {

        return new Builder();
    }

    public String getEventId() {

        return eventId;
    }

    public String getTenantId() {

        return tenantId;
    }

    public String getRootTenantId() {

        return rootTenantId;
    }

    public String getWebhookId() {

        return webhookId;
    }

    public String getEventType() {

        return eventType;
    }

    public String getTopic() {

        return topic;
    }

    public String getEndpointUrl() {

        return endpointUrl;
    }

    /**
     * @return Copy of the exact request body bytes that were signed.
     */
    public byte[] getPayload() {

        return payload.clone();
    }

    public Map<String, String> getHeaders() {

        return headers;
    }

    public DeliveryStatus getStatus() {

        return status;
    }

    public int getAttemptCount() {

        return attemptCount;
    }

    public Duration getFirstRetryDelay() {

        return firstRetryDelay;
    }

    public Duration getRetryWindow() {

        return retryWindow;
    }

    public Integer getLastStatusCode() {

        return lastStatusCode;
    }

    public String getLastError() {

        return lastError;
    }

    private static String require(String value, String name, int maxLength) {

        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " is required.");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(name + " is longer than " + maxLength + " characters.");
        }
        return value;
    }

    private static Duration requireNonNegative(Duration value, String name) {

        if (value == null || value.isNegative()) {
            throw new IllegalArgumentException(name + " is required and must not be negative.");
        }
        return value;
    }

    /**
     * Builder for {@link NewDelivery}.
     */
    public static final class Builder {

        private String eventId;
        private String tenantId;
        private String rootTenantId;
        private String webhookId;
        private String eventType;
        private String topic;
        private String endpointUrl;
        private byte[] payload;
        private Map<String, String> headers;
        private DeliveryStatus status = DeliveryStatus.PENDING;
        private int attemptCount;
        private Duration firstRetryDelay;
        private Duration retryWindow;
        private Integer lastStatusCode;
        private String lastError;

        private Builder() {

        }

        public Builder eventId(String eventId) {

            this.eventId = eventId;
            return this;
        }

        public Builder tenantId(String tenantId) {

            this.tenantId = tenantId;
            return this;
        }

        public Builder rootTenantId(String rootTenantId) {

            this.rootTenantId = rootTenantId;
            return this;
        }

        public Builder webhookId(String webhookId) {

            this.webhookId = webhookId;
            return this;
        }

        public Builder eventType(String eventType) {

            this.eventType = eventType;
            return this;
        }

        public Builder topic(String topic) {

            this.topic = topic;
            return this;
        }

        public Builder endpointUrl(String endpointUrl) {

            this.endpointUrl = endpointUrl;
            return this;
        }

        /**
         * @param payload Exact request body bytes that were signed.
         * @return This builder.
         */
        public Builder payload(byte[] payload) {

            this.payload = payload == null ? null : payload.clone();
            return this;
        }

        public Builder headers(Map<String, String> headers) {

            this.headers = headers == null ? null : new LinkedHashMap<>(headers);
            return this;
        }

        /**
         * @param status PENDING for a retryable failure, FAILED for a permanent one.
         * @return This builder.
         */
        public Builder status(DeliveryStatus status) {

            this.status = status;
            return this;
        }

        /**
         * @param attemptCount 1 if IS already tried, 0 if it stored the event without trying.
         * @return This builder.
         */
        public Builder attemptCount(int attemptCount) {

            this.attemptCount = attemptCount;
            return this;
        }

        /**
         * @param firstRetryDelay Shortest wait before the webhook is tried again after this failed try. It sets the
         *                        webhook's next attempt time, not a time for this row. Unused when attemptCount is 0.
         * @return This builder.
         */
        public Builder firstRetryDelay(Duration firstRetryDelay) {

            this.firstRetryDelay = firstRetryDelay;
            return this;
        }

        /**
         * @param retryWindow How long the delivery is retried, measured from now.
         * @return This builder.
         */
        public Builder retryWindow(Duration retryWindow) {

            this.retryWindow = retryWindow;
            return this;
        }

        public Builder lastStatusCode(Integer lastStatusCode) {

            this.lastStatusCode = lastStatusCode;
            return this;
        }

        /**
         * @param lastError Short reason for the last failure. Must not contain secrets or payload data.
         * @return This builder.
         */
        public Builder lastError(String lastError) {

            this.lastError = lastError;
            return this;
        }

        public NewDelivery build() {

            return new NewDelivery(this);
        }
    }
}
