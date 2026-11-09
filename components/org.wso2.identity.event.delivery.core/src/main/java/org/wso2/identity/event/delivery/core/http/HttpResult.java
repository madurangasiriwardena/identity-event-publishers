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

package org.wso2.identity.event.delivery.core.http;

import java.nio.charset.StandardCharsets;

/**
 * Status, Retry-After header and the first bytes of the body of an endpoint response.
 */
public final class HttpResult {

    private final int statusCode;
    private final String retryAfter;
    private final byte[] bodyPrefix;

    /**
     * @param statusCode HTTP status code.
     * @param retryAfter Value of the Retry-After header, or null.
     * @param bodyPrefix Up to the configured number of body bytes; the rest is discarded.
     */
    public HttpResult(int statusCode, String retryAfter, byte[] bodyPrefix) {

        this.statusCode = statusCode;
        this.retryAfter = retryAfter;
        this.bodyPrefix = bodyPrefix == null ? new byte[0] : bodyPrefix.clone();
    }

    public int getStatusCode() {

        return statusCode;
    }

    public String getRetryAfter() {

        return retryAfter;
    }

    /**
     * @return The kept body bytes as UTF-8 text.
     */
    public String getBodyText() {

        return new String(bodyPrefix, StandardCharsets.UTF_8);
    }
}
