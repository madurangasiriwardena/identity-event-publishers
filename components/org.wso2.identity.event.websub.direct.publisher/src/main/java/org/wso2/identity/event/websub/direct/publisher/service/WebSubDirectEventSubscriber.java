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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.organization.management.service.exception.OrganizationManagementException;
import org.wso2.carbon.identity.subscription.management.api.model.Subscription;
import org.wso2.carbon.identity.subscription.management.api.model.SubscriptionStatus;
import org.wso2.carbon.identity.subscription.management.api.model.WebhookSubscriptionRequest;
import org.wso2.carbon.identity.subscription.management.api.model.WebhookUnsubscriptionRequest;
import org.wso2.carbon.identity.subscription.management.api.service.EventSubscriber;
import org.wso2.identity.event.delivery.core.security.EndpointValidator;
import org.wso2.identity.event.delivery.core.security.InvalidEndpointException;
import org.wso2.identity.event.delivery.core.store.DeliveryStore;
import org.wso2.identity.event.delivery.core.store.DeliveryStoreException;
import org.wso2.identity.event.websub.direct.publisher.constant.WebSubDirectConstants;
import org.wso2.identity.event.websub.direct.publisher.constant.WebSubDirectConstants.Hub;
import org.wso2.identity.event.websub.direct.publisher.internal.org.OrganizationSupport;
import org.wso2.identity.event.websub.direct.publisher.internal.verify.SubscriptionVerifier;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Subscribes and unsubscribes webhook channels by verifying the endpoint directly (design section 5.0).
 * <p>
 * Subscribe: each channel is accepted only if the endpoint echoes the challenge. Unsubscribe: IS sends the
 * unsubscribe verification as a courtesy, but delivery stops whatever the endpoint answers. The channel's pending
 * retries are cancelled; only if that cancel fails is the unsubscription reported as an error, so the admin retries
 * it and the Retry Worker does not keep sending to a deactivated webhook.
 */
public class WebSubDirectEventSubscriber implements EventSubscriber {

    private static final Log LOG = LogFactory.getLog(WebSubDirectEventSubscriber.class);

    private final SubscriptionVerifier verifier;
    private final EndpointValidator validator;
    private final OrganizationSupport organizations;
    private final DeliveryStore store;

    public WebSubDirectEventSubscriber(SubscriptionVerifier verifier, EndpointValidator validator,
                                       OrganizationSupport organizations, DeliveryStore store) {

        this.verifier = verifier;
        this.validator = validator;
        this.organizations = organizations;
        this.store = store;
    }

    @Override
    public String getAssociatedAdapter() {

        return WebSubDirectConstants.ADAPTER_NAME;
    }

    @Override
    public List<Subscription> subscribe(WebhookSubscriptionRequest request, String tenantDomain) {

        List<Subscription> results = new ArrayList<>();
        URI endpoint;
        try {
            endpoint = validator.validate(request.getEndpoint());
        } catch (InvalidEndpointException e) {
            LOG.debug("Webhook subscription rejected in tenant " + tenantDomain + ": " + e.getMessage());
            for (String channel : request.getChannelsToSubscribe()) {
                results.add(result(channel, SubscriptionStatus.SUBSCRIPTION_ERROR));
            }
            return results;
        }
        for (String channel : request.getChannelsToSubscribe()) {
            boolean confirmed = false;
            try {
                String topic = organizations.topic(channel, request.getEventProfileName(),
                        request.getEventProfileVersion(), tenantDomain);
                confirmed = verifier.verify(endpoint, Hub.MODE_SUBSCRIBE, topic);
            } catch (OrganizationManagementException | RuntimeException e) {
                LOG.error("Webhook subscription to channel " + channel + " in tenant " + tenantDomain
                        + " could not be verified.", e);
            }
            results.add(result(channel, confirmed ? SubscriptionStatus.SUBSCRIPTION_ACCEPTED
                    : SubscriptionStatus.SUBSCRIPTION_ERROR));
        }
        return results;
    }

    @Override
    public List<Subscription> unsubscribe(WebhookUnsubscriptionRequest request, String tenantDomain) {

        List<Subscription> results = new ArrayList<>();
        URI endpoint = null;
        try {
            endpoint = validator.validate(request.getEndpoint());
        } catch (InvalidEndpointException e) {
            LOG.debug("Unsubscribe verification skipped for an invalid endpoint in tenant " + tenantDomain + ".");
        }
        for (String channel : request.getChannelToUnsubscribe()) {
            SubscriptionStatus status = SubscriptionStatus.UNSUBSCRIPTION_ACCEPTED;
            try {
                String topic = organizations.topic(channel, request.getEventProfileName(),
                        request.getEventProfileVersion(), tenantDomain);
                if (endpoint != null && !verifier.verify(endpoint, Hub.MODE_UNSUBSCRIBE, topic)) {
                    LOG.debug("Endpoint did not confirm unsubscription from " + topic
                            + ". Delivery stops regardless.");
                }
                int cancelled = store.cancelPendingByEndpoint(request.getEndpoint(), topic);
                if (cancelled > 0) {
                    LOG.info("Cancelled " + cancelled + " pending webhook deliveries of topic " + topic + ".");
                }
            } catch (DeliveryStoreException | OrganizationManagementException | RuntimeException e) {
                LOG.error("Pending deliveries of channel " + channel + " in tenant " + tenantDomain
                        + " could not be cancelled. Retry the unsubscription.", e);
                status = SubscriptionStatus.UNSUBSCRIPTION_ERROR;
            }
            results.add(result(channel, status));
        }
        return results;
    }

    private static Subscription result(String channel, SubscriptionStatus status) {

        return Subscription.builder().channelUri(channel).status(status).build();
    }
}
