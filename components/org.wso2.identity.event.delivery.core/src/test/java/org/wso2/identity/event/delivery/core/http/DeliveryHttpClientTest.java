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

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.wso2.identity.event.delivery.core.security.AddressPolicy;
import org.wso2.identity.event.delivery.core.security.BlockedAddressException;
import org.wso2.identity.event.delivery.core.security.GuardedDnsResolver;
import org.wso2.identity.event.delivery.core.testsupport.TestEndpoint;
import org.wso2.identity.event.delivery.core.testsupport.TestEndpoint.Reply;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Tests for {@link DeliveryHttpClient} against a local endpoint.
 */
public class DeliveryHttpClientTest {

    private TestEndpoint endpoint;
    private DeliveryHttpClient client;

    @BeforeMethod
    public void setUp() throws Exception {

        endpoint = new TestEndpoint();
        client = new DeliveryHttpClient(new DeliveryHttpClient.Settings(1000, 1000, 500, 10, 5, 1),
                new GuardedDnsResolver(AddressPolicy.allowAll()));
    }

    @AfterMethod
    public void tearDown() throws Exception {

        client.close();
        endpoint.close();
    }

    @Test
    public void testPostSendsBytesAndReturnsRetryAfter() throws Exception {

        endpoint.respond(request -> new Reply(503, "busy", 0, "60", null));
        byte[] body = "{\"a\":\"é\"}".getBytes(StandardCharsets.UTF_8);

        HttpResult result = client.post(URI.create(endpoint.url("/hook")), body,
                Collections.singletonMap("X-Test", "1")).get(5, TimeUnit.SECONDS);

        Assert.assertEquals(result.getStatusCode(), 503);
        Assert.assertEquals(result.getRetryAfter(), "60");
        Assert.assertEquals(result.getBodyText(), "", "Deliveries keep no response body.");
        Assert.assertEquals(endpoint.received().get(0).body, body);
        Assert.assertEquals(endpoint.received().get(0).header("X-Test"), "1");
        // Without a Content-Type header from the caller, the entity's type is used. Deliveries always set one.
        Assert.assertTrue(endpoint.received().get(0).header("Content-Type").startsWith("application/json"));
    }

    @Test
    public void testGetKeepsOnlyBodyPrefix() throws Exception {

        char[] big = new char[100000];
        Arrays.fill(big, 'x');
        endpoint.respond(request -> new Reply(200, new String(big), 0, null, null));

        HttpResult result = client.get(URI.create(endpoint.url("/hook")), 10).get(5, TimeUnit.SECONDS);

        Assert.assertEquals(result.getStatusCode(), 200);
        Assert.assertEquals(result.getBodyText(), "xxxxxxxxxx");
        Assert.assertNull(result.getRetryAfter());
    }

    @Test
    public void testBlockedAddressFails() throws Exception {

        DeliveryHttpClient guarded = new DeliveryHttpClient(new DeliveryHttpClient.Settings(1000, 1000, 500, 10, 5,
                1), new GuardedDnsResolver(AddressPolicy.publicOnly()));
        try {
            guarded.post(URI.create(endpoint.url("/hook")), new byte[]{1}, Collections.<String, String>emptyMap())
                    .get(5, TimeUnit.SECONDS);
            Assert.fail("A blocked address must fail.");
        } catch (ExecutionException e) {
            Assert.assertTrue(e.getCause() instanceof BlockedAddressException, "Got " + e.getCause());
            Assert.assertTrue(endpoint.received().isEmpty());
        } finally {
            guarded.close();
        }
    }
}
