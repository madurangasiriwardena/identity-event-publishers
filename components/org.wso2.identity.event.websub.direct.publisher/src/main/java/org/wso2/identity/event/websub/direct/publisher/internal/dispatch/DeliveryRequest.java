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

package org.wso2.identity.event.websub.direct.publisher.internal.dispatch;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One event for one webhook, ready to send: signed body bytes, headers and identifiers.
 * <p>
 * Equality is identity on purpose: the dispatcher tracks requests in flight by instance.
 */
public final class DeliveryRequest {

    private final String eventTenantDomain;
    private final String tenantId;
    private final String rootTenantId;
    private final String webhookId;
    private final String eventId;
    private final String eventType;
    private final String eventProfileName;
    private final String topic;
    private final String endpointUrl;
    private final byte[] payload;
    private final Map<String, String> headers;

    private DeliveryRequest(Builder builder) {

        this.eventTenantDomain = builder.eventTenantDomain;
        this.tenantId = builder.tenantId;
        this.rootTenantId = builder.rootTenantId;
        this.webhookId = builder.webhookId;
        this.eventId = builder.eventId;
        this.eventType = builder.eventType;
        this.eventProfileName = builder.eventProfileName;
        this.topic = builder.topic;
        this.endpointUrl = builder.endpointUrl;
        this.payload = builder.payload == null ? new byte[0] : builder.payload.clone();
        this.headers = Collections.unmodifiableMap(builder.headers == null ? new LinkedHashMap<String, String>()
                : new LinkedHashMap<>(builder.headers));
    }

    public static Builder builder() {

        return new Builder();
    }

    /**
     * @return Tenant domain in which the event was raised. Diagnostic logs are written there.
     */
    public String getEventTenantDomain() {

        return eventTenantDomain;
    }

    /**
     * @return Organization ID of the webhook owner.
     */
    public String getTenantId() {

        return tenantId;
    }

    public String getRootTenantId() {

        return rootTenantId;
    }

    public String getWebhookId() {

        return webhookId;
    }

    public String getEventId() {

        return eventId;
    }

    public String getEventType() {

        return eventType;
    }

    public String getEventProfileName() {

        return eventProfileName;
    }

    public String getTopic() {

        return topic;
    }

    public String getEndpointUrl() {

        return endpointUrl;
    }

    public byte[] getPayload() {

        return payload.clone();
    }

    public Map<String, String> getHeaders() {

        return headers;
    }

    /**
     * Builder for {@link DeliveryRequest}.
     */
    public static final class Builder {

        private String eventTenantDomain;
        private String tenantId;
        private String rootTenantId;
        private String webhookId;
        private String eventId;
        private String eventType;
        private String eventProfileName;
        private String topic;
        private String endpointUrl;
        private byte[] payload;
        private Map<String, String> headers;

        private Builder() {

        }

        public Builder eventTenantDomain(String eventTenantDomain) {

            this.eventTenantDomain = eventTenantDomain;
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

        public Builder eventId(String eventId) {

            this.eventId = eventId;
            return this;
        }

        public Builder eventType(String eventType) {

            this.eventType = eventType;
            return this;
        }

        public Builder eventProfileName(String eventProfileName) {

            this.eventProfileName = eventProfileName;
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

        public Builder payload(byte[] payload) {

            this.payload = payload == null ? null : payload.clone();
            return this;
        }

        public Builder headers(Map<String, String> headers) {

            this.headers = headers == null ? null : new LinkedHashMap<>(headers);
            return this;
        }

        public DeliveryRequest build() {

            return new DeliveryRequest(this);
        }
    }
}
