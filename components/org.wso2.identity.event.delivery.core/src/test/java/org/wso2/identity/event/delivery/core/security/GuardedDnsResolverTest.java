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
import org.testng.Assert;
import org.testng.annotations.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Tests for {@link GuardedDnsResolver}. A stub resolver stands in for DNS.
 */
public class GuardedDnsResolverTest {

    private static DnsResolver answering(String... literals) {

        return host -> {
            InetAddress[] addresses = new InetAddress[literals.length];
            for (int i = 0; i < literals.length; i++) {
                addresses[i] = InetAddress.getByName(literals[i]);
            }
            return addresses;
        };
    }

    @Test
    public void testReturnsPublicAddresses() throws UnknownHostException {

        GuardedDnsResolver resolver = new GuardedDnsResolver(answering("8.8.8.8", "2606:4700:4700::1111"),
                AddressPolicy.publicOnly());

        InetAddress[] addresses = resolver.resolve("hooks.example.com");

        Assert.assertEquals(addresses.length, 2);
        Assert.assertEquals(addresses[0].getHostAddress(), "8.8.8.8");
    }

    @Test
    public void testRejectsHostWithAnyBlockedAddress() {

        GuardedDnsResolver resolver = new GuardedDnsResolver(answering("8.8.8.8", "169.254.169.254"),
                AddressPolicy.publicOnly());

        try {
            resolver.resolve("rebind.example.com");
            Assert.fail("A host with a metadata address must be rejected.");
        } catch (BlockedAddressException e) {
            Assert.assertEquals(e.getHost(), "rebind.example.com");
            Assert.assertFalse(e.getMessage().contains("169.254"), "The message must not reveal the address.");
        } catch (UnknownHostException e) {
            Assert.fail("Expected BlockedAddressException, got " + e);
        }
    }

    @Test
    public void testAllowAllPolicyPermitsLoopback() throws UnknownHostException {

        GuardedDnsResolver resolver = new GuardedDnsResolver(answering("127.0.0.1"), AddressPolicy.allowAll());

        Assert.assertEquals(resolver.resolve("localhost").length, 1);
    }

    @Test(expectedExceptions = UnknownHostException.class)
    public void testEmptyAnswerIsUnknownHost() throws UnknownHostException {

        new GuardedDnsResolver(host -> new InetAddress[0], AddressPolicy.publicOnly()).resolve("empty.example");
    }

    @Test(expectedExceptions = UnknownHostException.class)
    public void testNullAnswerIsUnknownHost() throws UnknownHostException {

        new GuardedDnsResolver(host -> null, AddressPolicy.publicOnly()).resolve("null.example");
    }

    @Test
    public void testIpLiteralHostIsChecked() {

        // The system resolver parses IP literals without a DNS query.
        GuardedDnsResolver resolver = new GuardedDnsResolver(AddressPolicy.publicOnly());

        Assert.assertThrows(BlockedAddressException.class, () -> resolver.resolve("10.0.0.5"));
    }
}
