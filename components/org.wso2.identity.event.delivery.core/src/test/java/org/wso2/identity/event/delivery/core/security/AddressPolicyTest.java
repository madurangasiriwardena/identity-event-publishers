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

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Tests for {@link AddressPolicy}. All inputs are IP literals, so no DNS lookup happens.
 */
public class AddressPolicyTest {

    @DataProvider
    public Object[][] blockedAddresses() {

        return new Object[][]{
                {"0.0.0.0"},
                {"10.1.2.3"},
                {"100.64.0.1"},
                {"127.0.0.1"},
                {"169.254.169.254"},      // cloud metadata service
                {"172.16.0.1"},
                {"172.31.255.255"},
                {"192.0.0.8"},
                {"192.0.2.10"},
                {"192.168.1.1"},
                {"198.18.0.1"},
                {"198.51.100.7"},
                {"203.0.113.9"},
                {"224.0.0.251"},
                {"255.255.255.255"},
                {"::"},
                {"::1"},
                {"fe80::1"},
                {"fc00::1"},
                {"fd12:3456:789a::1"},
                {"ff02::1"},
                {"2001:db8::1"},
                {"2001:0:4136:e378::1"},  // Teredo
                {"::ffff:10.0.0.1"},       // IPv4-mapped private
                {"::ffff:169.254.169.254"},
                {"64:ff9b::a9fe:a9fe"},    // NAT64 of 169.254.169.254
                {"64:ff9b:1:a9fe:a9:fe00::"}, // local-use NAT64 /48 of 169.254.169.254
                {"2002:a00:1::1"},         // 6to4 of 10.0.0.1
                {"100::1"},
        };
    }

    @Test(dataProvider = "blockedAddresses")
    public void testPublicOnlyBlocksNonPublicAddress(String literal) throws UnknownHostException {

        Assert.assertFalse(AddressPolicy.publicOnly().isAllowed(InetAddress.getByName(literal)),
                literal + " must be blocked");
    }

    @DataProvider
    public Object[][] publicAddresses() {

        return new Object[][]{
                {"8.8.8.8"},
                {"1.1.1.1"},
                {"172.32.0.1"},           // just outside 172.16.0.0/12
                {"100.128.0.1"},          // just outside 100.64.0.0/10
                {"2606:4700:4700::1111"},
                {"::ffff:8.8.8.8"},
                {"64:ff9b::808:808"},     // NAT64 of 8.8.8.8
                {"2002:808:808::1"},      // 6to4 of 8.8.8.8
        };
    }

    @Test(dataProvider = "publicAddresses")
    public void testPublicOnlyAllowsPublicAddress(String literal) throws UnknownHostException {

        Assert.assertTrue(AddressPolicy.publicOnly().isAllowed(InetAddress.getByName(literal)),
                literal + " must be allowed");
    }

    @Test
    public void testAllowAllAllowsLoopback() throws UnknownHostException {

        Assert.assertTrue(AddressPolicy.allowAll().isAllowed(InetAddress.getByName("127.0.0.1")));
        Assert.assertTrue(AddressPolicy.allowAll().allowsNonPublicAddresses());
        Assert.assertFalse(AddressPolicy.publicOnly().allowsNonPublicAddresses());
    }

    @Test
    public void testCidrBlockBoundaries() throws UnknownHostException {

        AddressPolicy.CidrBlock block = AddressPolicy.CidrBlock.parse("100.64.0.0/10");
        Assert.assertTrue(block.contains(InetAddress.getByName("100.127.255.255").getAddress()));
        Assert.assertFalse(block.contains(InetAddress.getByName("100.128.0.0").getAddress()));
        Assert.assertFalse(block.contains(InetAddress.getByName("::1").getAddress()));
    }

    @DataProvider
    public Object[][] invalidCidrBlocks() {

        return new Object[][]{
                {"10.0.0.0"},
                {"10.0.0.0/33"},
                {"example.com/8"},
        };
    }

    @Test(dataProvider = "invalidCidrBlocks", expectedExceptions = IllegalArgumentException.class)
    public void testInvalidCidrBlock(String cidr) {

        AddressPolicy.CidrBlock.parse(cidr);
    }
}
