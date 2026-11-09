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

import java.time.Instant;
import java.util.UUID;

/**
 * A delivery that a Retry Worker replica has claimed. Its stored request is read with {@link DeliveryQueue#open}.
 */
public final class ClaimedDelivery {

    private final UUID id;
    private final String eventId;
    private final String tenantId;
    private final String webhookId;
    private final String eventType;
    private final String endpointUrl;
    private final int attemptCount;
    private final Instant expiresAt;
    private final int endpointFailures;
    private final byte[] payload;
    private final byte[] encodedHeaders;

    ClaimedDelivery(UUID id, String eventId, String tenantId, String webhookId, String eventType, String endpointUrl,
                    int attemptCount, Instant expiresAt, int endpointFailures, byte[] payload,
                    byte[] encodedHeaders) {

        this.id = id;
        this.eventId = eventId;
        this.tenantId = tenantId;
        this.webhookId = webhookId;
        this.eventType = eventType;
        this.endpointUrl = endpointUrl;
        this.attemptCount = attemptCount;
        this.expiresAt = expiresAt;
        this.endpointFailures = endpointFailures;
        this.payload = payload;
        this.encodedHeaders = encodedHeaders;
    }

    public UUID getId() {

        return id;
    }

    public String getEventId() {

        return eventId;
    }

    public String getTenantId() {

        return tenantId;
    }

    public String getWebhookId() {

        return webhookId;
    }

    public String getEventType() {

        return eventType;
    }

    public String getEndpointUrl() {

        return endpointUrl;
    }

    /**
     * @return Attempts made before this claim, including the first attempt by Identity Server.
     */
    public int getAttemptCount() {

        return attemptCount;
    }

    public Instant getExpiresAt() {

        return expiresAt;
    }

    /**
     * @return Consecutive failures of this webhook's endpoint when the row was claimed. A value above zero means the
     *         endpoint was failing, so the worker sends one row as a probe before the others.
     */
    public int getEndpointFailures() {

        return endpointFailures;
    }

    byte[] getPayload() {

        return payload.clone();
    }

    byte[] getEncodedHeaders() {

        return encodedHeaders.clone();
    }
}
