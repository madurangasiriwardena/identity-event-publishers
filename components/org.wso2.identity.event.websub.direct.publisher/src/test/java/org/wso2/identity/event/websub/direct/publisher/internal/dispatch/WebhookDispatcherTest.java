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

package org.wso2.identity.event.websub.direct.publisher.internal.dispatch;

import org.mockito.ArgumentCaptor;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.wso2.identity.event.delivery.core.http.DeliveryHttpClient;
import org.wso2.identity.event.delivery.core.http.ResponseClassifier;
import org.wso2.identity.event.delivery.core.security.AddressPolicy;
import org.wso2.identity.event.delivery.core.security.EndpointValidator;
import org.wso2.identity.event.delivery.core.security.GuardedDnsResolver;
import org.wso2.identity.event.delivery.core.signing.DeliveryHeaders;
import org.wso2.identity.event.delivery.core.signing.PayloadSigner;
import org.wso2.identity.event.delivery.core.store.DeliveryStatus;
import org.wso2.identity.event.delivery.core.store.DeliveryStore;
import org.wso2.identity.event.delivery.core.store.NewDelivery;
import org.wso2.identity.event.delivery.core.testsupport.TestEndpoint;
import org.wso2.identity.event.delivery.core.testsupport.TestEndpoint.Reply;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link WebhookDispatcher} with a real HTTP client against a local endpoint. The database is a mock.
 */
public class WebhookDispatcherTest {

    private static final String SECRET = "s3cr3t";
    private static final String TOPIC = "myorg.org-1.schema.wso2.v1.event.login";
    private static final byte[] BODY = "{\"jti\":\"e-1\",\"user\":\"José 山田\"}"
            .getBytes(StandardCharsets.UTF_8);
    private static final long WAIT_MILLIS = 5000;

    private TestEndpoint endpoint;
    private DeliveryHttpClient client;
    private DeliveryStore store;
    private FailureStoreWriter writer;
    private DeliveryObserver observer;
    private DispatchMetrics metrics;
    private ExecutorService callbacks;

    @BeforeMethod
    public void setUp() throws Exception {

        endpoint = new TestEndpoint();
        client = new DeliveryHttpClient(new DeliveryHttpClient.Settings(1000, 400, 500, 10, 5, 1),
                new GuardedDnsResolver(AddressPolicy.allowAll()));
        store = mock(DeliveryStore.class);
        when(store.insert(any(NewDelivery.class))).thenReturn(true);
        observer = mock(DeliveryObserver.class);
        metrics = new DispatchMetrics();
        writer = new FailureStoreWriter(store, new FailureStoreWriter.Settings(1, 100, Duration.ofSeconds(5),
                1000, Duration.ofHours(24)), observer, metrics);
        writer.start();
        callbacks = Executors.newFixedThreadPool(2);
    }

    @AfterMethod
    public void tearDown() throws Exception {

        writer.shutdown(Duration.ofSeconds(1));
        callbacks.shutdownNow();
        client.close();
        endpoint.close();
    }

    private WebhookDispatcher dispatcher(int maxInFlight, int circuitThreshold) {

        return dispatcher(maxInFlight, circuitThreshold, client);
    }

    private WebhookDispatcher dispatcher(int maxInFlight, int circuitThreshold, DeliveryHttpClient httpClient) {

        return new WebhookDispatcher(httpClient, new EndpointValidator(true), new ResponseClassifier(),
                new CircuitBreaker(circuitThreshold, Duration.ofMinutes(1)), writer, observer, metrics, callbacks,
                new WebhookDispatcher.Settings(maxInFlight, Duration.ofSeconds(30)));
    }

    private DeliveryRequest request(String url) {

        return DeliveryRequest.builder()
                .eventTenantDomain("myorg")
                .tenantId("org-1")
                .rootTenantId("org-1")
                .webhookId("wh-1")
                .eventId("e-1")
                .eventType("https://schemas.identity.wso2.org/events/login/event-type/loginSuccess")
                .eventProfileName("WSO2")
                .topic(TOPIC)
                .endpointUrl(url)
                .payload(BODY)
                .headers(DeliveryHeaders.build("https://hub.example.com", TOPIC, "e-1",
                        PayloadSigner.sign(SECRET, BODY)))
                .build();
    }

    private NewDelivery storedDelivery() throws Exception {

        ArgumentCaptor<NewDelivery> captor = ArgumentCaptor.forClass(NewDelivery.class);
        verify(store, timeout(WAIT_MILLIS)).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    public void testSuccessSendsExactBytesAndHeaders() throws Exception {

        WebhookDispatcher dispatcher = dispatcher(10, 5);

        dispatcher.deliver(request(endpoint.url("/hook")));

        verify(observer, timeout(WAIT_MILLIS)).onDelivered(any(DeliveryRequest.class), any(Integer.class));
        TestEndpoint.Received received = endpoint.received().get(0);
        Assert.assertEquals(received.method, "POST");
        Assert.assertEquals(received.body, BODY, "The body must be the exact signed bytes.");
        Assert.assertEquals(received.header("X-Hub-Signature"), PayloadSigner.sign(SECRET, received.body),
                "A receiver must be able to verify the signature over the received body.");
        Assert.assertEquals(received.header("x-hub-messageId"), "e-1");
        Assert.assertEquals(received.header("Content-Type"), "application/json");
        Assert.assertEquals(received.header("Link"),
                "<https://hub.example.com>; rel=\"hub\", <" + TOPIC + ">; rel=\"self\"");
        verify(store, after(300).never()).insert(any(NewDelivery.class));
        Assert.assertEquals(metrics.getDirectDelivered(), 1);
    }

    @Test
    public void testServerErrorIsStoredForRetry() throws Exception {

        endpoint.respond(request -> Reply.status(503));

        dispatcher(10, 5).deliver(request(endpoint.url("/hook")));

        NewDelivery stored = storedDelivery();
        Assert.assertEquals(stored.getStatus(), DeliveryStatus.PENDING);
        Assert.assertEquals(stored.getAttemptCount(), 1);
        Assert.assertEquals(stored.getLastStatusCode(), Integer.valueOf(503));
        Assert.assertEquals(stored.getLastError(), "HTTP 503");
        Assert.assertEquals(stored.getFirstRetryDelay(), Duration.ofSeconds(30));
        Assert.assertEquals(stored.getPayload(), BODY);
        Assert.assertEquals(stored.getHeaders().get(DeliveryHeaders.SIGNATURE), PayloadSigner.sign(SECRET, BODY));
        verify(observer, timeout(WAIT_MILLIS)).onStored(any(DeliveryRequest.class), any(DeliveryStatus.class));
    }

    @Test
    public void testRetryAfterExtendsFirstDelay() throws Exception {

        endpoint.respond(request -> new Reply(429, "", 0, "120", null));

        dispatcher(10, 5).deliver(request(endpoint.url("/hook")));

        Assert.assertEquals(storedDelivery().getFirstRetryDelay(), Duration.ofSeconds(120));
    }

    @Test
    public void testClientErrorFailsForGood() throws Exception {

        endpoint.respond(request -> Reply.status(422));

        dispatcher(10, 5).deliver(request(endpoint.url("/hook")));

        NewDelivery stored = storedDelivery();
        Assert.assertEquals(stored.getStatus(), DeliveryStatus.FAILED);
        Assert.assertEquals(stored.getAttemptCount(), 1);
    }

    @Test
    public void testEndpointRejectionIsRetried() throws Exception {

        endpoint.respond(request -> Reply.status(410));

        dispatcher(10, 5).deliver(request(endpoint.url("/hook")));

        Assert.assertEquals(storedDelivery().getStatus(), DeliveryStatus.PENDING);
    }

    @Test
    public void testRedirectIsNotFollowed() throws Exception {

        endpoint.respond(request -> new Reply(302, "", 0, null, endpoint.url("/elsewhere")));

        dispatcher(10, 5).deliver(request(endpoint.url("/hook")));

        Assert.assertEquals(storedDelivery().getStatus(), DeliveryStatus.FAILED);
        Assert.assertEquals(endpoint.received().size(), 1, "The redirect target must not be called.");
    }

    @Test
    public void testTimeoutIsStoredForRetry() throws Exception {

        endpoint.respond(request -> new Reply(200, "", 1500, null, null));

        dispatcher(10, 5).deliver(request(endpoint.url("/hook")));

        NewDelivery stored = storedDelivery();
        Assert.assertEquals(stored.getStatus(), DeliveryStatus.PENDING);
        Assert.assertNull(stored.getLastStatusCode());
        Assert.assertEquals(stored.getLastError(), "SocketTimeoutException");
    }

    @Test
    public void testBlockedAddressFailsWithoutRequest() throws Exception {

        DeliveryHttpClient guarded = new DeliveryHttpClient(new DeliveryHttpClient.Settings(1000, 400, 500, 10, 5, 1),
                new GuardedDnsResolver(AddressPolicy.publicOnly()));
        try {
            dispatcher(10, 5, guarded).deliver(request(endpoint.url("/hook")));

            NewDelivery stored = storedDelivery();
            Assert.assertEquals(stored.getStatus(), DeliveryStatus.FAILED);
            Assert.assertEquals(stored.getAttemptCount(), 0);
            Assert.assertTrue(stored.getLastError().contains("not allowed"));
            Assert.assertTrue(endpoint.received().isEmpty(), "No request may reach a blocked address.");
        } finally {
            guarded.close();
        }
    }

    @Test
    public void testInvalidEndpointFailsWithoutRequest() throws Exception {

        dispatcher(10, 5).deliver(request("ftp://hooks.example.com/"));

        NewDelivery stored = storedDelivery();
        Assert.assertEquals(stored.getStatus(), DeliveryStatus.FAILED);
        Assert.assertEquals(stored.getAttemptCount(), 0);
    }

    @Test
    public void testOpenCircuitSkipsDirectAttempt() throws Exception {

        endpoint.respond(request -> Reply.status(503));
        WebhookDispatcher dispatcher = dispatcher(10, 2);
        dispatcher.deliver(request(endpoint.url("/hook")));
        verify(store, timeout(WAIT_MILLIS).times(1)).insert(any(NewDelivery.class));
        dispatcher.deliver(request(endpoint.url("/hook")));
        verify(store, timeout(WAIT_MILLIS).times(2)).insert(any(NewDelivery.class));

        dispatcher.deliver(request(endpoint.url("/hook")));

        ArgumentCaptor<NewDelivery> captor = ArgumentCaptor.forClass(NewDelivery.class);
        verify(store, timeout(WAIT_MILLIS).times(3)).insert(captor.capture());
        Assert.assertEquals(captor.getValue().getAttemptCount(), 0);
        Assert.assertEquals(endpoint.received().size(), 2, "The third event must not be sent.");
        Assert.assertEquals(metrics.getCircuitOpened(), 1);
        Assert.assertEquals(metrics.getSkippedByCircuit(), 1);
    }

    @Test
    public void testCapacityLimitStoresWithoutAttempt() throws Exception {

        endpoint.respond(request -> new Reply(200, "", 300, null, null));
        WebhookDispatcher dispatcher = dispatcher(1, 5);
        dispatcher.deliver(request(endpoint.url("/hook")));

        dispatcher.deliver(request(endpoint.url("/hook")));

        NewDelivery stored = storedDelivery();
        Assert.assertEquals(stored.getAttemptCount(), 0);
        Assert.assertEquals(metrics.getSkippedByCapacity(), 1);
        verify(observer, timeout(WAIT_MILLIS)).onDelivered(any(DeliveryRequest.class), any(Integer.class));
    }

    @Test
    public void testShutdownStoresRequestsInFlight() throws Exception {

        endpoint.respond(request -> new Reply(200, "", 2000, null, null));
        DeliveryHttpClient slowClient = new DeliveryHttpClient(new DeliveryHttpClient.Settings(1000, 5000, 500, 10,
                5, 1), new GuardedDnsResolver(AddressPolicy.allowAll()));
        try {
            WebhookDispatcher dispatcher = dispatcher(10, 5, slowClient);
            dispatcher.deliver(request(endpoint.url("/hook")));
            waitUntil(() -> !endpoint.received().isEmpty());
            Assert.assertEquals(dispatcher.inFlight(), 1);

            dispatcher.shutdown(Duration.ofMillis(100));

            NewDelivery stored = storedDelivery();
            Assert.assertEquals(stored.getStatus(), DeliveryStatus.PENDING);
            Assert.assertEquals(stored.getAttemptCount(), 0);
            Assert.assertEquals(dispatcher.inFlight(), 0);

            dispatcher.deliver(request(endpoint.url("/hook")));
            verify(store, timeout(WAIT_MILLIS).times(2)).insert(any(NewDelivery.class));
            Assert.assertEquals(endpoint.received().size(), 1, "No new attempt after shutdown started.");
        } finally {
            slowClient.close();
        }
    }

    @Test
    public void testObserverFailureDoesNotBreakDelivery() throws Exception {

        doThrow(new IllegalStateException("broken observer")).when(observer)
                .onDelivered(any(DeliveryRequest.class), any(Integer.class));
        WebhookDispatcher dispatcher = dispatcher(10, 5);

        dispatcher.deliver(request(endpoint.url("/hook")));
        verify(observer, timeout(WAIT_MILLIS)).onDelivered(any(DeliveryRequest.class), any(Integer.class));
        dispatcher.deliver(request(endpoint.url("/hook")));

        verify(observer, timeout(WAIT_MILLIS).times(2)).onDelivered(any(DeliveryRequest.class), any(Integer.class));
        verify(store, never()).countPending(anyString());
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                Assert.fail("Condition not met in time.");
            }
            Thread.sleep(20);
        }
    }
}
