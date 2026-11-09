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

import java.net.UnknownHostException;

/**
 * Raised when a webhook host resolves to an address that webhooks must not reach, such as a private, loopback or
 * cloud metadata address.
 * <p>
 * It extends {@link UnknownHostException} because the HTTP client's DNS resolver contract only allows that type.
 * Callers tell it apart from a real DNS failure by its type, because a blocked address must not be retried.
 */
public class BlockedAddressException extends UnknownHostException {

    private static final long serialVersionUID = 4061823170293840221L;

    private final String host;

    /**
     * @param host Host name from the webhook URL.
     */
    public BlockedAddressException(String host) {

        super("Webhook host '" + host + "' resolves to an address that is not allowed for webhooks.");
        this.host = host;
    }

    /**
     * @return Host name from the webhook URL.
     */
    public String getHost() {

        return host;
    }
}
