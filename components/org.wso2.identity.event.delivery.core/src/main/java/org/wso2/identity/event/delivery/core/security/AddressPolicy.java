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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Decides whether a resolved IP address may receive webhook traffic.
 * <p>
 * Webhook URLs come from customers, and both Identity Server and the Retry Worker run inside the cloud network.
 * Without this check a webhook could reach internal services or the cloud metadata service. Only public unicast
 * addresses are allowed. IPv6 addresses that embed an IPv4 address (IPv4-mapped, NAT64 and 6to4) are checked by
 * their IPv4 part.
 */
public final class AddressPolicy {

    private static final List<CidrBlock> BLOCKED_RANGES = Collections.unmodifiableList(Arrays.asList(
            // IPv4
            CidrBlock.parse("0.0.0.0/8"),           // "this" network
            CidrBlock.parse("10.0.0.0/8"),          // private
            CidrBlock.parse("100.64.0.0/10"),       // carrier-grade NAT
            CidrBlock.parse("127.0.0.0/8"),         // loopback
            CidrBlock.parse("169.254.0.0/16"),      // link-local, includes cloud metadata 169.254.169.254
            CidrBlock.parse("172.16.0.0/12"),       // private
            CidrBlock.parse("192.0.0.0/24"),        // IETF protocol assignments
            CidrBlock.parse("192.0.2.0/24"),        // documentation
            CidrBlock.parse("192.88.99.0/24"),      // 6to4 relay anycast
            CidrBlock.parse("192.168.0.0/16"),      // private
            CidrBlock.parse("198.18.0.0/15"),       // benchmarking
            CidrBlock.parse("198.51.100.0/24"),     // documentation
            CidrBlock.parse("203.0.113.0/24"),      // documentation
            CidrBlock.parse("224.0.0.0/4"),         // multicast
            CidrBlock.parse("240.0.0.0/4"),         // reserved and broadcast
            // IPv6
            CidrBlock.parse("::/96"),               // unspecified, loopback and IPv4-compatible
            CidrBlock.parse("100::/64"),            // discard
            CidrBlock.parse("2001::/32"),           // Teredo
            CidrBlock.parse("2001:db8::/32"),       // documentation
            CidrBlock.parse("fc00::/7"),            // unique local
            CidrBlock.parse("fe80::/10"),           // link-local
            CidrBlock.parse("fec0::/10"),           // site-local (deprecated)
            CidrBlock.parse("ff00::/8")             // multicast
    ));

    private static final CidrBlock NAT64_WELL_KNOWN = CidrBlock.parse("64:ff9b::/96");
    private static final CidrBlock NAT64_LOCAL = CidrBlock.parse("64:ff9b:1::/48");
    private static final CidrBlock SIX_TO_FOUR = CidrBlock.parse("2002::/16");

    private static final AddressPolicy PUBLIC_ONLY = new AddressPolicy(false);
    private static final AddressPolicy ALLOW_ALL = new AddressPolicy(true);

    private final boolean allowNonPublic;

    private AddressPolicy(boolean allowNonPublic) {

        this.allowNonPublic = allowNonPublic;
    }

    /**
     * @return The production policy, which allows only public unicast addresses.
     */
    public static AddressPolicy publicOnly() {

        return PUBLIC_ONLY;
    }

    /**
     * Returns a policy that allows every address. Use it only for local development and tests, where the test
     * endpoint runs on localhost.
     *
     * @return A policy that allows every address.
     */
    public static AddressPolicy allowAll() {

        return ALLOW_ALL;
    }

    /**
     * @param address Resolved address of a webhook host.
     * @return True if a webhook request may be sent to this address.
     */
    public boolean isAllowed(InetAddress address) {

        return allowNonPublic || !isNonPublic(address);
    }

    /**
     * @return True if this policy allows every address.
     */
    public boolean allowsNonPublicAddresses() {

        return allowNonPublic;
    }

    static boolean isNonPublic(InetAddress address) {

        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] raw = address.getAddress();
        byte[] embeddedIpv4 = embeddedIpv4(raw);
        if (embeddedIpv4 != null) {
            return isNonPublic(toAddress(embeddedIpv4));
        }
        for (CidrBlock block : BLOCKED_RANGES) {
            if (block.contains(raw)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] embeddedIpv4(byte[] raw) {

        if (raw.length != 16) {
            return null;
        }
        // IPv4-mapped addresses (::ffff:a.b.c.d) need no case here: Java always returns them as Inet4Address.
        if (NAT64_WELL_KNOWN.contains(raw)) {
            return Arrays.copyOfRange(raw, 12, 16);
        }
        if (NAT64_LOCAL.contains(raw)) {
            // RFC 6052 with a /48 prefix: the IPv4 address sits in bytes 6-7 and 9-10; byte 8 is reserved.
            return new byte[]{raw[6], raw[7], raw[9], raw[10]};
        }
        if (SIX_TO_FOUR.contains(raw)) {
            return Arrays.copyOfRange(raw, 2, 6);
        }
        return null;
    }

    private static InetAddress toAddress(byte[] raw) {

        try {
            return InetAddress.getByAddress(raw);
        } catch (UnknownHostException e) {
            // Only thrown for an illegal length, which cannot happen for 4 bytes.
            throw new IllegalStateException(e);
        }
    }

    /**
     * An IP network in CIDR notation.
     */
    static final class CidrBlock {

        private final byte[] network;
        private final int prefixLength;

        private CidrBlock(byte[] network, int prefixLength) {

            this.network = network;
            this.prefixLength = prefixLength;
        }

        static CidrBlock parse(String cidr) {

            int slash = cidr.indexOf('/');
            if (slash < 0) {
                throw new IllegalArgumentException("Not a CIDR block: " + cidr);
            }
            String literal = cidr.substring(0, slash);
            if (!literal.matches("[0-9a-fA-F:.]+")) {
                // Guards against a host name, which would trigger a DNS lookup.
                throw new IllegalArgumentException("Not an IP literal: " + literal);
            }
            byte[] network;
            try {
                network = InetAddress.getByName(literal).getAddress();
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Not an IP literal: " + literal, e);
            }
            int prefixLength = Integer.parseInt(cidr.substring(slash + 1));
            if (prefixLength < 0 || prefixLength > network.length * 8) {
                throw new IllegalArgumentException("Invalid prefix length in: " + cidr);
            }
            return new CidrBlock(network, prefixLength);
        }

        boolean contains(byte[] address) {

            if (address.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            int remainingBits = prefixLength % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
