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

package org.wso2.identity.event.websub.direct.publisher.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.core.URLBuilderException;
import org.wso2.carbon.identity.event.publisher.api.exception.EventPublisherException;
import org.wso2.carbon.identity.event.publisher.api.exception.EventPublisherServerException;
import org.wso2.carbon.identity.event.publisher.api.model.EventContext;
import org.wso2.carbon.identity.event.publisher.api.model.SecurityEventTokenPayload;
import org.wso2.carbon.identity.event.publisher.api.service.EventPublisher;
import org.wso2.carbon.identity.organization.management.service.exception.OrganizationManagementException;
import org.wso2.carbon.identity.webhook.management.api.exception.WebhookMgtException;
import org.wso2.carbon.identity.webhook.management.api.model.Webhook;
import org.wso2.carbon.identity.webhook.management.api.service.WebhookManagementService;
import org.wso2.identity.event.delivery.core.signing.DeliveryHeaders;
import org.wso2.identity.event.delivery.core.signing.PayloadSigner;
import org.wso2.identity.event.websub.direct.publisher.constant.WebSubDirectConstants;
import org.wso2.identity.event.websub.direct.publisher.internal.dispatch.DeliveryRequest;
import org.wso2.identity.event.websub.direct.publisher.internal.dispatch.DeliveryStrategy;
import org.wso2.identity.event.websub.direct.publisher.internal.org.OrganizationSupport;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Publishes an event to every webhook subscribed to its channel, one request per webhook.
 * <p>
 * Today the hub fans an event out to its subscribers. With this adapter, IS does the fan-out: it serializes the
 * payload once per owning organization (the issuer differs per owner), signs it once per webhook (the secret differs
 * per webhook), and hands each delivery to the {@link DeliveryStrategy}. Publishing never waits for an endpoint.
 */
public class WebSubDirectEventPublisher implements EventPublisher {

    private static final Log LOG = LogFactory.getLog(WebSubDirectEventPublisher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
    private static final String ERROR_CODE_WEBHOOK_LOOKUP = "WSDIRECT-65001";

    private final Supplier<WebhookManagementService> webhookManagementService;
    private final OrganizationSupport organizations;
    private final DeliveryStrategy deliveryStrategy;
    private final String hubUrl;

    /**
     * @param webhookManagementService Supplies the OSGi webhook management service at call time.
     * @param organizations            Organization lookups.
     * @param deliveryStrategy         Sends each prepared delivery.
     * @param hubUrl                   Hub URL for the {@code Link} header.
     */
    public WebSubDirectEventPublisher(Supplier<WebhookManagementService> webhookManagementService,
                                      OrganizationSupport organizations, DeliveryStrategy deliveryStrategy,
                                      String hubUrl) {

        this.webhookManagementService = webhookManagementService;
        this.organizations = organizations;
        this.deliveryStrategy = deliveryStrategy;
        this.hubUrl = hubUrl;
    }

    @Override
    public String getAssociatedAdapter() {

        return WebSubDirectConstants.ADAPTER_NAME;
    }

    @Override
    public boolean canHandleEvent(EventContext eventContext) throws EventPublisherException {

        return !subscribedWebhooks(eventContext).isEmpty();
    }

    @Override
    public void publish(SecurityEventTokenPayload payload, EventContext eventContext) throws EventPublisherException {

        List<Webhook> webhooks = subscribedWebhooks(eventContext);
        if (webhooks.isEmpty()) {
            return;
        }
        String eventTenantDomain = eventContext.getTenantDomain();
        boolean organizationAware = organizations.isOrganizationAwarePublishing(eventTenantDomain);
        String eventType = payload.getEvents() == null || payload.getEvents().isEmpty() ? eventContext.getEventUri()
                : payload.getEvents().keySet().iterator().next();
        Map<String, OwnerContext> owners = new HashMap<>();
        for (Webhook webhook : webhooks) {
            String owner = StringUtils.isNotBlank(webhook.getOwnerTenantDomain()) ? webhook.getOwnerTenantDomain()
                    : eventTenantDomain;
            try {
                OwnerContext ownerContext = owners.get(owner);
                if (ownerContext == null) {
                    ownerContext = ownerContext(payload, eventContext, owner, organizationAware);
                    owners.put(owner, ownerContext);
                }
                String secret = webhook.getDecryptedSecret();
                String signature = StringUtils.isEmpty(secret) ? null
                        : PayloadSigner.sign(secret, ownerContext.body);
                deliveryStrategy.deliver(DeliveryRequest.builder()
                        .eventTenantDomain(eventTenantDomain)
                        .tenantId(ownerContext.organizationId)
                        .rootTenantId(ownerContext.rootOrganizationId)
                        .webhookId(webhook.getId())
                        .eventId(payload.getJti())
                        .eventType(eventType)
                        .eventProfileName(eventContext.getEventProfileName())
                        .topic(ownerContext.topic)
                        .endpointUrl(webhook.getEndpoint())
                        .payload(ownerContext.body)
                        .headers(DeliveryHeaders.build(hubUrl, ownerContext.topic, payload.getJti(), signature))
                        .build());
            } catch (WebhookMgtException | OrganizationManagementException | URLBuilderException
                     | JsonProcessingException | RuntimeException e) {
                // One webhook must not stop delivery to the others.
                LOG.error("Event " + payload.getJti() + " cannot be prepared for webhook " + webhook.getId()
                        + " of tenant " + owner + ". The event is not delivered to this webhook.", e);
            }
        }
    }

    private OwnerContext ownerContext(SecurityEventTokenPayload payload, EventContext eventContext, String owner,
                                      boolean organizationAware)
            throws OrganizationManagementException, URLBuilderException, JsonProcessingException {

        SecurityEventTokenPayload ownerPayload = organizationAware
                ? payload.toBuilder().iss(organizations.transmitterIssuer(owner)).build() : payload;
        return new OwnerContext(
                MAPPER.writeValueAsBytes(ownerPayload),
                organizations.topic(eventContext.getEventUri(), eventContext.getEventProfileName(),
                        eventContext.getEventProfileVersion(), owner),
                organizations.organizationId(owner),
                organizations.rootOrganizationId(owner));
    }

    private List<Webhook> subscribedWebhooks(EventContext eventContext) throws EventPublisherException {

        try {
            // ACTIVE or PARTIALLY_ACTIVE webhooks whose channel is SUBSCRIPTION_ACCEPTED, which with this adapter
            // means the endpoint passed verification. It includes ancestors' webhooks for organization events.
            List<Webhook> webhooks = webhookManagementService.get().getSubscriptionAcceptedWebhooksForOrganization(
                    eventContext.getEventProfileName(), eventContext.getEventProfileVersion(),
                    eventContext.getEventUri(), eventContext.getTenantDomain());
            return webhooks == null ? Collections.<Webhook>emptyList() : webhooks;
        } catch (WebhookMgtException e) {
            throw new EventPublisherServerException(ERROR_CODE_WEBHOOK_LOOKUP,
                    "Error while retrieving subscribed webhooks.",
                    "Webhooks subscribed to " + eventContext.getEventUri() + " cannot be retrieved.", e);
        }
    }

    /**
     * Values shared by all webhooks of one owning organization.
     */
    private static final class OwnerContext {

        private final byte[] body;
        private final String topic;
        private final String organizationId;
        private final String rootOrganizationId;

        private OwnerContext(byte[] body, String topic, String organizationId, String rootOrganizationId) {

            this.body = body;
            this.topic = topic;
            this.organizationId = organizationId;
            this.rootOrganizationId = rootOrganizationId;
        }
    }
}
