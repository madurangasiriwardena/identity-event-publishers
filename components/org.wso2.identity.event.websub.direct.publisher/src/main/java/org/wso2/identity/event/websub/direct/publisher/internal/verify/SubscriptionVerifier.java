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

package org.wso2.identity.event.websub.direct.publisher.internal.verify;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.identity.event.delivery.core.http.DeliveryHttpClient;
import org.wso2.identity.event.delivery.core.http.HttpResult;
import org.wso2.identity.event.websub.direct.publisher.constant.WebSubDirectConstants.Hub;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends the WebSub intent verification request to a webhook endpoint, as described in the product API contract.
 * <p>
 * The endpoint passes when it answers 2xx with the exact challenge as the body. Today the hub does this after
 * accepting the subscription; here Identity Server does it before reporting the result, so an accepted subscription
 * means the endpoint really confirmed it.
 */
public class SubscriptionVerifier {

    private static final Log LOG = LogFactory.getLog(SubscriptionVerifier.class);
    // A UUID challenge is 36 characters; keep a little more to detect extra content.
    private static final int MAX_BODY_BYTES = 1024;

    private final DeliveryHttpClient client;
    private final long timeoutMillis;
    private final long leaseSeconds;

    /**
     * @param client        HTTP client with the SSRF guard.
     * @param timeoutMillis Maximum wait for the endpoint's answer.
     * @param leaseSeconds  Value sent as {@code hub.lease_seconds} on subscribe. It is not enforced.
     */
    public SubscriptionVerifier(DeliveryHttpClient client, long timeoutMillis, long leaseSeconds) {

        this.client = client;
        this.timeoutMillis = timeoutMillis;
        this.leaseSeconds = leaseSeconds;
    }

    /**
     * Verifies one subscription or unsubscription.
     *
     * @param endpoint Validated webhook endpoint.
     * @param mode     {@link Hub#MODE_SUBSCRIBE} or {@link Hub#MODE_UNSUBSCRIBE}.
     * @param topic    WebSub topic of the channel.
     * @return True if the endpoint confirmed.
     */
    public boolean verify(URI endpoint, String mode, String topic) {

        String challenge = UUID.randomUUID().toString();
        URI request = verificationUri(endpoint, mode, topic, challenge);
        Future<HttpResult> future = client.get(request, MAX_BODY_BYTES);
        try {
            HttpResult result = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            boolean confirmed = result.getStatusCode() >= 200 && result.getStatusCode() < 300
                    && challenge.equals(result.getBodyText().trim());
            if (!confirmed && LOG.isDebugEnabled()) {
                LOG.debug("Webhook endpoint did not confirm " + mode + " for topic " + topic + ". Status: "
                        + result.getStatusCode());
            }
            return confirmed;
        } catch (TimeoutException e) {
            future.cancel(true);
            LOG.debug("Webhook endpoint did not answer the " + mode + " verification for topic " + topic
                    + " within " + timeoutMillis + " ms.");
            return false;
        } catch (ExecutionException e) {
            LOG.debug("Webhook " + mode + " verification for topic " + topic + " failed: "
                    + e.getCause().getClass().getSimpleName());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Adds the WebSub query parameters to the endpoint URL. Existing query parameters are kept; a fragment is
     * removed, because it is never sent.
     */
    URI verificationUri(URI endpoint, String mode, String topic, String challenge) {

        StringBuilder query = new StringBuilder()
                .append(Hub.MODE).append('=').append(encode(mode))
                .append('&').append(Hub.TOPIC).append('=').append(encode(topic))
                .append('&').append(Hub.CHALLENGE).append('=').append(encode(challenge));
        if (Hub.MODE_SUBSCRIBE.equals(mode)) {
            query.append('&').append(Hub.LEASE_SECONDS).append('=').append(leaseSeconds);
        }
        String base = endpoint.toString();
        int fragment = base.indexOf('#');
        if (fragment >= 0) {
            base = base.substring(0, fragment);
        }
        return URI.create(base + (endpoint.getRawQuery() == null ? "?" : "&") + query);
    }

    private static String encode(String value) {

        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
