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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.wso2.carbon.identity.event.publisher.api.exception.EventPublisherException;
import org.wso2.carbon.identity.event.publisher.api.model.EventContext;
import org.wso2.carbon.identity.event.publisher.api.model.EventPayload;
import org.wso2.carbon.identity.event.publisher.api.model.SecurityEventTokenPayload;
import org.wso2.carbon.identity.webhook.management.api.exception.WebhookMgtServerException;
import org.wso2.carbon.identity.webhook.management.api.model.Webhook;
import org.wso2.carbon.identity.webhook.management.api.service.WebhookManagementService;
import org.wso2.identity.event.delivery.core.signing.DeliveryHeaders;
import org.wso2.identity.event.delivery.core.signing.PayloadSigner;
import org.wso2.identity.event.websub.direct.publisher.constant.WebSubDirectConstants;
import org.wso2.identity.event.websub.direct.publisher.internal.dispatch.DeliveryRequest;
import org.wso2.identity.event.websub.direct.publisher.internal.org.OrganizationSupport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link WebSubDirectEventPublisher}.
 */
public class WebSubDirectEventPublisherTest {

    private static final String CHANNEL = "https://schemas.identity.wso2.org/events/login";
    private static final String EVENT_URI = "https://schemas.identity.wso2.org/events/login/event-type/loginSuccess";
    private static final String TOPIC = "myorg.org-1.schema.wso2.v1.event.login";

    private WebhookManagementService webhookService;
    private OrganizationSupport organizations;
    private List<DeliveryRequest> delivered;
    private WebSubDirectEventPublisher publisher;

    /**
     * Event data used by the test payload.
     */
    public static final class LoginEvent extends EventPayload {

        private final String user;

        LoginEvent(String user) {

            this.user = user;
        }

        public String getUser() {

            return user;
        }
    }

    @BeforeMethod
    public void setUp() throws Exception {

        webhookService = mock(WebhookManagementService.class);
        organizations = mock(OrganizationSupport.class);
        when(organizations.topic(CHANNEL, "WSO2", "v1", "myorg")).thenReturn(TOPIC);
        when(organizations.organizationId("myorg")).thenReturn("org-1");
        when(organizations.rootOrganizationId("myorg")).thenReturn("org-1");
        delivered = new ArrayList<>();
        publisher = new WebSubDirectEventPublisher(() -> webhookService, organizations, delivered::add,
                "https://hub.example.com");
    }

    private static EventContext context(String tenantDomain) {

        return EventContext.builder().tenantDomain(tenantDomain).eventUri(CHANNEL).eventProfileName("WSO2")
                .eventProfileVersion("v1").build();
    }

    private static SecurityEventTokenPayload payload() {

        return SecurityEventTokenPayload.builder()
                .iss("https://api.example.com/t/sub")
                .jti("e-1")
                .iat(1751705149662L)
                .rci("r-1")
                .events(Collections.<String, EventPayload>singletonMap(EVENT_URI, new LoginEvent("José")))
                .build();
    }

    private static Webhook webhook(String id, String secret, String owner) throws Exception {

        Webhook webhook = mock(Webhook.class);
        when(webhook.getId()).thenReturn(id);
        when(webhook.getEndpoint()).thenReturn("https://hooks.example.com/" + id);
        when(webhook.getDecryptedSecret()).thenReturn(secret);
        when(webhook.getOwnerTenantDomain()).thenReturn(owner);
        return webhook;
    }

    private void subscribed(String tenantDomain, Webhook... webhooks) throws Exception {

        when(webhookService.getSubscriptionAcceptedWebhooksForOrganization("WSO2", "v1", CHANNEL, tenantDomain))
                .thenReturn(Arrays.asList(webhooks));
    }

    @Test
    public void testOneSignedDeliveryPerWebhook() throws Exception {

        subscribed("myorg", webhook("wh-1", "secret-1", "myorg"), webhook("wh-2", "secret-2", null));

        publisher.publish(payload(), context("myorg"));

        Assert.assertEquals(delivered.size(), 2);
        DeliveryRequest first = delivered.get(0);
        DeliveryRequest second = delivered.get(1);
        Assert.assertEquals(first.getPayload(), second.getPayload(), "One body per owner organization.");
        Assert.assertEquals(first.getHeaders().get(DeliveryHeaders.SIGNATURE),
                PayloadSigner.sign("secret-1", first.getPayload()));
        Assert.assertEquals(second.getHeaders().get(DeliveryHeaders.SIGNATURE),
                PayloadSigner.sign("secret-2", second.getPayload()));
        Assert.assertEquals(first.getHeaders().get(DeliveryHeaders.MESSAGE_ID), "e-1");
        Assert.assertTrue(first.getHeaders().get(DeliveryHeaders.LINK).contains("<" + TOPIC + ">; rel=\"self\""));
        Assert.assertEquals(first.getTopic(), TOPIC);
        Assert.assertEquals(first.getTenantId(), "org-1");
        Assert.assertEquals(first.getRootTenantId(), "org-1");
        Assert.assertEquals(first.getEventType(), EVENT_URI);
        Assert.assertEquals(first.getEndpointUrl(), "https://hooks.example.com/wh-1");
        Assert.assertEquals(first.getEventTenantDomain(), "myorg");

        JsonNode body = new ObjectMapper().readTree(first.getPayload());
        Assert.assertEquals(body.get("jti").asText(), "e-1");
        Assert.assertEquals(body.get("iss").asText(), "https://api.example.com/t/sub",
                "Without organization-aware publishing the issuer is unchanged.");
        Assert.assertEquals(body.get("events").get(EVENT_URI).get("user").asText(), "José");
    }

    @Test
    public void testWebhookWithoutSecretIsNotSigned() throws Exception {

        subscribed("myorg", webhook("wh-1", "", "myorg"));

        publisher.publish(payload(), context("myorg"));

        Assert.assertFalse(delivered.get(0).getHeaders().containsKey(DeliveryHeaders.SIGNATURE));
    }

    @Test
    public void testOrganizationEventUsesOwnerIssuerAndTopic() throws Exception {

        when(organizations.isOrganizationAwarePublishing("sub")).thenReturn(true);
        when(organizations.transmitterIssuer("myorg")).thenReturn("https://api.example.com/t/myorg");
        subscribed("sub", webhook("wh-1", "secret-1", "myorg"));

        publisher.publish(payload(), context("sub"));

        Assert.assertEquals(delivered.size(), 1);
        Assert.assertEquals(delivered.get(0).getTopic(), TOPIC, "The topic of the owning organization is used.");
        Assert.assertEquals(new ObjectMapper().readTree(delivered.get(0).getPayload()).get("iss").asText(),
                "https://api.example.com/t/myorg");
    }

    @Test
    public void testFailureForOneWebhookDoesNotStopOthers() throws Exception {

        Webhook broken = webhook("wh-broken", null, "myorg");
        when(broken.getDecryptedSecret()).thenThrow(new WebhookMgtServerException("secret store down"));
        subscribed("myorg", broken, webhook("wh-2", "secret-2", "myorg"));

        publisher.publish(payload(), context("myorg"));

        Assert.assertEquals(delivered.size(), 1);
        Assert.assertEquals(delivered.get(0).getWebhookId(), "wh-2");
    }

    @Test
    public void testNoWebhooks() throws Exception {

        when(webhookService.getSubscriptionAcceptedWebhooksForOrganization(anyString(), anyString(), anyString(),
                anyString())).thenReturn(null);

        Assert.assertFalse(publisher.canHandleEvent(context("myorg")));
        publisher.publish(payload(), context("myorg"));
        Assert.assertTrue(delivered.isEmpty());
    }

    @Test
    public void testCanHandleEvent() throws Exception {

        subscribed("myorg", webhook("wh-1", "s", "myorg"));

        Assert.assertTrue(publisher.canHandleEvent(context("myorg")));
        Assert.assertEquals(publisher.getAssociatedAdapter(), WebSubDirectConstants.ADAPTER_NAME);
    }

    @Test(expectedExceptions = EventPublisherException.class)
    public void testLookupFailureIsReported() throws Exception {

        when(webhookService.getSubscriptionAcceptedWebhooksForOrganization(anyString(), anyString(), anyString(),
                anyString())).thenThrow(new WebhookMgtServerException("db down"));

        publisher.publish(payload(), context("myorg"));
    }

    @Test
    public void testEventWithoutEventsUsesChannelAsType() throws Exception {

        subscribed("myorg", webhook("wh-1", "s", "myorg"));

        publisher.publish(SecurityEventTokenPayload.builder().jti("e-2").iat(1L).build(), context("myorg"));

        Assert.assertEquals(delivered.get(0).getEventType(), CHANNEL);
    }
}
