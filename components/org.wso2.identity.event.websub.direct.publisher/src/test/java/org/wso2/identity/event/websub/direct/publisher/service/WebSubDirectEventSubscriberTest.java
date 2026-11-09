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

import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.wso2.carbon.identity.organization.management.service.exception.OrganizationManagementException;
import org.wso2.carbon.identity.subscription.management.api.model.Subscription;
import org.wso2.carbon.identity.subscription.management.api.model.SubscriptionStatus;
import org.wso2.carbon.identity.subscription.management.api.model.WebhookSubscriptionRequest;
import org.wso2.carbon.identity.subscription.management.api.model.WebhookUnsubscriptionRequest;
import org.wso2.identity.event.delivery.core.security.EndpointValidator;
import org.wso2.identity.event.delivery.core.store.DeliveryStore;
import org.wso2.identity.event.delivery.core.store.DeliveryStoreException;
import org.wso2.identity.event.websub.direct.publisher.constant.WebSubDirectConstants;
import org.wso2.identity.event.websub.direct.publisher.constant.WebSubDirectConstants.Hub;
import org.wso2.identity.event.websub.direct.publisher.internal.org.OrganizationSupport;
import org.wso2.identity.event.websub.direct.publisher.internal.verify.SubscriptionVerifier;

import java.net.URI;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link WebSubDirectEventSubscriber}.
 */
public class WebSubDirectEventSubscriberTest {

    private static final String LOGIN = "https://schemas.identity.wso2.org/events/login";
    private static final String SESSION = "https://schemas.identity.wso2.org/events/session";
    private static final String ENDPOINT = "https://hooks.example.com/hook";
    private static final String LOGIN_TOPIC = "myorg.org-1.schema.wso2.v1.event.login";
    private static final String SESSION_TOPIC = "myorg.org-1.schema.wso2.v1.event.session";

    private SubscriptionVerifier verifier;
    private OrganizationSupport organizations;
    private DeliveryStore store;
    private WebSubDirectEventSubscriber subscriber;

    @BeforeMethod
    public void setUp() throws Exception {

        verifier = mock(SubscriptionVerifier.class);
        organizations = mock(OrganizationSupport.class);
        store = mock(DeliveryStore.class);
        when(organizations.topic(LOGIN, "WSO2", "v1", "myorg")).thenReturn(LOGIN_TOPIC);
        when(organizations.topic(SESSION, "WSO2", "v1", "myorg")).thenReturn(SESSION_TOPIC);
        subscriber = new WebSubDirectEventSubscriber(verifier, new EndpointValidator(false), organizations, store);
    }

    private static WebhookSubscriptionRequest subscription(String endpoint) {

        return WebhookSubscriptionRequest.builder().channelsToSubscribe(Arrays.asList(LOGIN, SESSION))
                .eventProfileName("WSO2").eventProfileVersion("v1").endpoint(endpoint).secret("s").build();
    }

    private static WebhookUnsubscriptionRequest unsubscription() {

        return WebhookUnsubscriptionRequest.builder().channelsToUnsubscribe(Arrays.asList(LOGIN, SESSION))
                .eventProfileName("WSO2").eventProfileVersion("v1").endpoint(ENDPOINT).build();
    }

    @Test
    public void testEachChannelIsVerifiedSeparately() {

        when(verifier.verify(URI.create(ENDPOINT), Hub.MODE_SUBSCRIBE, LOGIN_TOPIC)).thenReturn(true);
        when(verifier.verify(URI.create(ENDPOINT), Hub.MODE_SUBSCRIBE, SESSION_TOPIC)).thenReturn(false);

        List<Subscription> results = subscriber.subscribe(subscription(ENDPOINT), "myorg");

        Assert.assertEquals(results.get(0).getChannelUri(), LOGIN);
        Assert.assertEquals(results.get(0).getStatus(), SubscriptionStatus.SUBSCRIPTION_ACCEPTED);
        Assert.assertEquals(results.get(1).getChannelUri(), SESSION);
        Assert.assertEquals(results.get(1).getStatus(), SubscriptionStatus.SUBSCRIPTION_ERROR);
        Assert.assertEquals(subscriber.getAssociatedAdapter(), WebSubDirectConstants.ADAPTER_NAME);
    }

    @Test
    public void testInvalidEndpointFailsAllChannels() {

        List<Subscription> results = subscriber.subscribe(subscription("http://hooks.example.com/hook"), "myorg");

        Assert.assertEquals(results.size(), 2);
        for (Subscription result : results) {
            Assert.assertEquals(result.getStatus(), SubscriptionStatus.SUBSCRIPTION_ERROR);
        }
        verify(verifier, never()).verify(any(URI.class), anyString(), anyString());
    }

    @Test
    public void testTopicFailureFailsThatChannel() throws Exception {

        when(organizations.topic(SESSION, "WSO2", "v1", "myorg"))
                .thenThrow(new OrganizationManagementException("org lookup failed"));
        when(verifier.verify(URI.create(ENDPOINT), Hub.MODE_SUBSCRIBE, LOGIN_TOPIC)).thenReturn(true);

        List<Subscription> results = subscriber.subscribe(subscription(ENDPOINT), "myorg");

        Assert.assertEquals(results.get(0).getStatus(), SubscriptionStatus.SUBSCRIPTION_ACCEPTED);
        Assert.assertEquals(results.get(1).getStatus(), SubscriptionStatus.SUBSCRIPTION_ERROR);
    }

    @Test
    public void testUnsubscribeStopsDeliveryWhateverTheEndpointAnswers() throws Exception {

        when(verifier.verify(any(URI.class), eq(Hub.MODE_UNSUBSCRIBE), anyString())).thenReturn(false);
        when(store.cancelPendingByEndpoint(ENDPOINT, LOGIN_TOPIC)).thenReturn(3);

        List<Subscription> results = subscriber.unsubscribe(unsubscription(), "myorg");

        for (Subscription result : results) {
            Assert.assertEquals(result.getStatus(), SubscriptionStatus.UNSUBSCRIPTION_ACCEPTED);
        }
        verify(store).cancelPendingByEndpoint(ENDPOINT, LOGIN_TOPIC);
        verify(store).cancelPendingByEndpoint(ENDPOINT, SESSION_TOPIC);
    }

    @Test
    public void testUnsubscribeReportsErrorWhenCancelFails() throws Exception {

        when(store.cancelPendingByEndpoint(ENDPOINT, SESSION_TOPIC))
                .thenThrow(new DeliveryStoreException("down", new SQLException("down")));

        List<Subscription> results = subscriber.unsubscribe(unsubscription(), "myorg");

        Assert.assertEquals(results.get(0).getStatus(), SubscriptionStatus.UNSUBSCRIPTION_ACCEPTED);
        Assert.assertEquals(results.get(1).getStatus(), SubscriptionStatus.UNSUBSCRIPTION_ERROR);
    }

    @Test
    public void testUnsubscribeOfInvalidEndpointStillCancels() throws Exception {

        WebhookUnsubscriptionRequest request = WebhookUnsubscriptionRequest.builder()
                .channelsToUnsubscribe(Arrays.asList(LOGIN)).eventProfileName("WSO2").eventProfileVersion("v1")
                .endpoint("http://legacy.example.com/hook").build();
        when(organizations.topic(LOGIN, "WSO2", "v1", "myorg")).thenReturn(LOGIN_TOPIC);

        List<Subscription> results = subscriber.unsubscribe(request, "myorg");

        Assert.assertEquals(results.get(0).getStatus(), SubscriptionStatus.UNSUBSCRIPTION_ACCEPTED);
        verify(verifier, never()).verify(any(URI.class), anyString(), anyString());
        verify(store).cancelPendingByEndpoint("http://legacy.example.com/hook", LOGIN_TOPIC);
    }
}
