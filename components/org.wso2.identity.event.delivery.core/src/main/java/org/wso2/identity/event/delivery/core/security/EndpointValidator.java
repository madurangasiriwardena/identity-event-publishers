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

package org.wso2.identity.event.delivery.core.security;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Checks the form of a webhook URL before any request is sent to it.
 * <p>
 * The address check happens later, in {@link GuardedDnsResolver}, because only the resolved address can be trusted.
 */
public final class EndpointValidator {

    private static final String HTTPS = "https";
    private static final String HTTP = "http";

    private final boolean allowHttp;

    /**
     * @param allowHttp True to accept plain HTTP URLs. Use it only for local development; production requires HTTPS.
     */
    public EndpointValidator(boolean allowHttp) {

        this.allowHttp = allowHttp;
    }

    /**
     * Validates a webhook URL.
     *
     * @param url Webhook URL as configured by the customer.
     * @return The parsed URL.
     * @throws InvalidEndpointException If the URL is malformed, uses a scheme that is not allowed, has no host, or
     *                                  contains user credentials.
     */
    public URI validate(String url) throws InvalidEndpointException {

        if (url == null || url.trim().isEmpty()) {
            throw new InvalidEndpointException("The webhook URL is empty.");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new InvalidEndpointException("The webhook URL is not a valid URL.", e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!HTTPS.equals(scheme) && !(allowHttp && HTTP.equals(scheme))) {
            throw new InvalidEndpointException(allowHttp ? "The webhook URL must use HTTPS or HTTP."
                    : "The webhook URL must use HTTPS.");
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new InvalidEndpointException("The webhook URL has no host.");
        }
        if (uri.getRawUserInfo() != null) {
            // Credentials in the URL would be stored and logged with it; endpoint auth uses headers instead.
            throw new InvalidEndpointException("The webhook URL must not contain user credentials.");
        }
        return uri;
    }
}
