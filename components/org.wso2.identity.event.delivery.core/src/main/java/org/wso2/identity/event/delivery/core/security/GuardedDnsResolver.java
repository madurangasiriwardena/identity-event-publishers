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

import org.apache.http.conn.DnsResolver;
import org.apache.http.impl.conn.SystemDefaultDnsResolver;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * DNS resolver for the webhook HTTP client that rejects hosts resolving to a blocked address.
 * <p>
 * The HTTP client connects to exactly the addresses this resolver returns, so the address that is checked is the
 * address that is used. A DNS answer that changes between a separate check and the connection (DNS rebinding)
 * therefore cannot bypass the check. TLS hostname verification still uses the host name from the URL.
 * <p>
 * If any resolved address is blocked, the whole host is rejected, instead of filtering that address out. A host
 * that mixes public and internal addresses is not a safe webhook target.
 */
public final class GuardedDnsResolver implements DnsResolver {

    private final DnsResolver delegate;
    private final AddressPolicy policy;

    /**
     * Creates a resolver that uses the system DNS resolver.
     *
     * @param policy Policy that decides which addresses are allowed.
     */
    public GuardedDnsResolver(AddressPolicy policy) {

        this(SystemDefaultDnsResolver.INSTANCE, policy);
    }

    /**
     * @param delegate Resolver that performs the lookup.
     * @param policy   Policy that decides which addresses are allowed.
     */
    public GuardedDnsResolver(DnsResolver delegate, AddressPolicy policy) {

        this.delegate = delegate;
        this.policy = policy;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {

        InetAddress[] addresses = delegate.resolve(host);
        if (addresses == null || addresses.length == 0) {
            throw new UnknownHostException("No address found for webhook host '" + host + "'.");
        }
        for (InetAddress address : addresses) {
            if (!policy.isAllowed(address)) {
                throw new BlockedAddressException(host);
            }
        }
        return addresses.clone();
    }
}
