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

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.wso2.identity.event.delivery.core.http.DeliveryHttpClient;
import org.wso2.identity.event.delivery.core.security.AddressPolicy;
import org.wso2.identity.event.delivery.core.security.GuardedDnsResolver;
import org.wso2.identity.event.delivery.core.testsupport.TestEndpoint;
import org.wso2.identity.event.delivery.core.testsupport.TestEndpoint.Reply;
import org.wso2.identity.event.websub.direct.publisher.constant.WebSubDirectConstants.Hub;

import java.net.URI;

/**
 * Tests for {@link SubscriptionVerifier} against a local endpoint.
 */
public class SubscriptionVerifierTest {

    private static final String TOPIC = "myorg.org-1.schema.wso2.v1.event.login";

    private TestEndpoint endpoint;
    private DeliveryHttpClient client;
    private SubscriptionVerifier verifier;

    @BeforeMethod
    public void setUp() throws Exception {

        endpoint = new TestEndpoint();
        client = new DeliveryHttpClient(new DeliveryHttpClient.Settings(1000, 2000, 500, 10, 5, 1),
                new GuardedDnsResolver(AddressPolicy.allowAll()));
        verifier = new SubscriptionVerifier(client, 1000, 864000);
    }

    @AfterMethod
    public void tearDown() throws Exception {

        client.close();
        endpoint.close();
    }

    @Test
    public void testEndpointEchoingChallengeIsConfirmed() {

        endpoint.respond(request -> new Reply(200, request.queryParameter(Hub.CHALLENGE) + "\n", 0, null, null));

        Assert.assertTrue(verifier.verify(URI.create(endpoint.url("/hook?token=abc")), Hub.MODE_SUBSCRIBE, TOPIC));

        TestEndpoint.Received received = endpoint.received().get(0);
        Assert.assertEquals(received.method, "GET");
        Assert.assertEquals(received.queryParameter(Hub.MODE), "subscribe");
        Assert.assertEquals(received.queryParameter(Hub.TOPIC), TOPIC);
        Assert.assertEquals(received.queryParameter(Hub.LEASE_SECONDS), "864000");
        Assert.assertEquals(received.queryParameter("token"), "abc", "Existing query parameters must be kept.");
        Assert.assertEquals(received.queryParameter(Hub.CHALLENGE).length(), 36, "The challenge is a UUID.");
    }

    @Test
    public void testUnsubscribeHasNoLease() {

        endpoint.respond(request -> new Reply(200, request.queryParameter(Hub.CHALLENGE), 0, null, null));

        Assert.assertTrue(verifier.verify(URI.create(endpoint.url("/hook")), Hub.MODE_UNSUBSCRIBE, TOPIC));
        Assert.assertNull(endpoint.received().get(0).queryParameter(Hub.LEASE_SECONDS));
        Assert.assertEquals(endpoint.received().get(0).queryParameter(Hub.MODE), "unsubscribe");
    }

    @Test
    public void testWrongChallengeIsRejected() {

        endpoint.respond(request -> new Reply(200, "ok", 0, null, null));

        Assert.assertFalse(verifier.verify(URI.create(endpoint.url("/hook")), Hub.MODE_SUBSCRIBE, TOPIC));
    }

    @Test
    public void testErrorStatusIsRejected() {

        endpoint.respond(request -> new Reply(404, request.queryParameter(Hub.CHALLENGE), 0, null, null));

        Assert.assertFalse(verifier.verify(URI.create(endpoint.url("/hook")), Hub.MODE_SUBSCRIBE, TOPIC));
    }

    @Test
    public void testSlowEndpointIsRejected() {

        endpoint.respond(request -> new Reply(200, request.queryParameter(Hub.CHALLENGE), 1500, null, null));

        Assert.assertFalse(verifier.verify(URI.create(endpoint.url("/hook")), Hub.MODE_SUBSCRIBE, TOPIC));
    }

    @Test
    public void testUnreachableEndpointIsRejected() {

        String url = endpoint.url("/hook");
        endpoint.close();

        Assert.assertFalse(verifier.verify(URI.create(url), Hub.MODE_SUBSCRIBE, TOPIC));
    }

    @Test
    public void testBlockedEndpointIsRejected() throws Exception {

        DeliveryHttpClient guarded = new DeliveryHttpClient(new DeliveryHttpClient.Settings(1000, 2000, 500, 10, 5,
                1), new GuardedDnsResolver(AddressPolicy.publicOnly()));
        try {
            SubscriptionVerifier guardedVerifier = new SubscriptionVerifier(guarded, 1000, 864000);

            Assert.assertFalse(guardedVerifier.verify(URI.create(endpoint.url("/hook")), Hub.MODE_SUBSCRIBE, TOPIC));
            Assert.assertTrue(endpoint.received().isEmpty());
        } finally {
            guarded.close();
        }
    }

    @Test
    public void testVerificationUriDropsFragmentAndEncodesValues() {

        URI uri = verifier.verificationUri(URI.create("https://hooks.example.com/a?x=1#frag"), Hub.MODE_SUBSCRIBE,
                "a b&c", "ch");

        Assert.assertEquals(uri.toString(), "https://hooks.example.com/a?x=1&hub.mode=subscribe&hub.topic=a+b%26c"
                + "&hub.challenge=ch&hub.lease_seconds=864000");
    }
}
