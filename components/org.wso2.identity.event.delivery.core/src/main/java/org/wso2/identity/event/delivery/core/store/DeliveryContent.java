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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The request of a stored delivery: the exact body bytes and the headers to send with them.
 */
public final class DeliveryContent {

    private final byte[] payload;
    private final Map<String, String> headers;

    DeliveryContent(byte[] payload, Map<String, String> headers) {

        this.payload = payload.clone();
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }

    /**
     * @return Copy of the exact request body bytes that were signed.
     */
    public byte[] getPayload() {

        return payload.clone();
    }

    /**
     * @return Headers to send, in order.
     */
    public Map<String, String> getHeaders() {

        return headers;
    }
}
