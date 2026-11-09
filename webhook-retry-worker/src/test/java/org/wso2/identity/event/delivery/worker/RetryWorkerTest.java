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

package org.wso2.identity.event.delivery.worker;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.wso2.identity.event.delivery.core.http.DeliveryHttpClient;
import org.wso2.identity.event.delivery.core.http.DeliveryOutcome;
import org.wso2.identity.event.delivery.core.http.ResponseClassifier;
import org.wso2.identity.event.delivery.core.retry.BackoffPolicy;
import org.wso2.identity.event.delivery.core.security.AddressPolicy;
import org.wso2.identity.event.delivery.core.security.EndpointValidator;
import org.wso2.identity.event.delivery.core.security.GuardedDnsResolver;
import org.wso2.identity.event.delivery.core.signing.DeliveryHeaders;
import org.wso2.identity.event.delivery.core.signing.PayloadSigner;
import org.wso2.identity.event.delivery.core.store.ClaimedDelivery;
import org.wso2.identity.event.delivery.core.store.DeliveryQueue;
import org.wso2.identity.event.delivery.core.store.DeliveryStore;
import org.wso2.identity.event.delivery.core.store.NewDelivery;
import org.wso2.identity.event.delivery.core.store.PostgresTestSupport;
import org.wso2.identity.event.delivery.core.testsupport.TestEndpoint;
import org.wso2.identity.event.delivery.core.testsupport.TestEndpoint.Reply;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tests the Retry Worker against PostgreSQL (real migrations and roles) and a local webhook endpoint.
 */
public class RetryWorkerTest {

    private static final String SECRET = "s3cr3t";
    private static final Duration LEASE = Duration.ofMinutes(2);

    private DeliveryStore store;
    private DeliveryQueue queue;
    private TestEndpoint endpoint;
    private DeliveryHttpClient client;
    private WorkerMetrics metrics;
    private AtomicBoolean stopping;

    @BeforeClass
    public void startDatabase() throws SQLException {

        // Debug on, so the debug messages are built and checked for errors; they go to no handler here.
        Logger.getLogger(LogSetup.APPLICATION_LOGGER).setLevel(Level.FINE);
        PostgresTestSupport.start();
        store = new DeliveryStore(PostgresTestSupport.isDataSource(), PostgresTestSupport.cipher());
        queue = new DeliveryQueue(PostgresTestSupport.workerDataSource(), PostgresTestSupport.cipher());
    }

    @BeforeMethod
    public void setUp() throws Exception {

        PostgresTestSupport.truncate();
        endpoint = new TestEndpoint();
        client = client(AddressPolicy.allowAll());
        metrics = new WorkerMetrics();
        stopping = new AtomicBoolean();
    }

    @AfterMethod
    public void tearDown() throws Exception {

        client.close();
        endpoint.close();
    }

    private static DeliveryHttpClient client(AddressPolicy policy) throws Exception {

        return new DeliveryHttpClient(new DeliveryHttpClient.Settings(1000, 1000, 500, 20, 10, 1),
                new GuardedDnsResolver(policy));
    }

    private WebhookGroupSender sender(DeliveryHttpClient httpClient) {

        return new WebhookGroupSender(queue, httpClient, new EndpointValidator(true), new ResponseClassifier(),
                new BackoffPolicy(Duration.ofSeconds(30), 2.0, Duration.ofHours(1), 0.0),
                Duration.ofMinutes(5), "worker-a", metrics, stopping, Clock.systemUTC());
    }

    private RetryWorker worker(WebhookGroupSender sender) {

        return new RetryWorker(queue, sender, metrics, new RetryWorker.Settings("worker-a", 50, 4, LEASE,
                Duration.ofSeconds(1), Duration.ofMillis(50), Duration.ofMillis(100)), Clock.systemUTC(), stopping);
    }

    private RetryWorker worker() {

        return worker(sender(client));
    }

    private NewDelivery.Builder delivery(String webhookId, String path) {

        NewDelivery.Builder builder = PostgresTestSupport.delivery(webhookId).endpointUrl(endpoint.url(path));
        NewDelivery draft = builder.build();
        return builder.headers(DeliveryHeaders.build("https://hub.example.com", draft.getTopic(),
                draft.getEventId(), PayloadSigner.sign(SECRET, draft.getPayload())));
    }

    private static long count(String sql) throws SQLException {

        try (Connection connection = PostgresTestSupport.adminDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    @Test
    public void testDeliveredRowIsDeletedAndRequestIsExact() throws Exception {

        NewDelivery delivery = delivery("wh-1", "/hook").build();
        store.insert(delivery);

        Assert.assertEquals(worker().runCycle(), 1);

        Assert.assertEquals(count("SELECT count(*) FROM webhook_delivery"), 0);
        TestEndpoint.Received received = endpoint.received().get(0);
        Assert.assertEquals(received.body, delivery.getPayload());
        Assert.assertTrue(new String(received.body, StandardCharsets.UTF_8).contains("José"));
        Assert.assertEquals(received.header("X-Hub-Signature"), PayloadSigner.sign(SECRET, received.body));
        Assert.assertEquals(received.header("x-hub-messageId"), delivery.getEventId());
        Assert.assertEquals(received.header("X-Webhook-Attempt"), "2", "IS made attempt 1.");
        Assert.assertEquals(metrics.getDelivered(), 1);
    }

    @Test
    public void testFailureDelaysWebhookAndReleasesItsOtherRows() throws Exception {

        endpoint.respond(request -> Reply.status(503));
        store.insert(delivery("wh-1", "/hook").build());
        store.insert(delivery("wh-1", "/hook").build());

        worker().runCycle();

        Assert.assertEquals(endpoint.received().size(), 1, "The second row must not be sent.");
        Assert.assertEquals(count("SELECT count(*) FROM webhook_delivery WHERE status = 'PENDING'"), 2);
        Assert.assertEquals(count("SELECT max(attempt_count) FROM webhook_delivery"), 2);
        Assert.assertEquals(count("SELECT count(*) FROM webhook_endpoint_state WHERE consecutive_failures = 3"
                + " AND next_attempt_at > now() + interval '25 seconds'"), 1, "Two failures in IS and one here.");
        Assert.assertEquals(metrics.getRetried(), 1);
        Assert.assertEquals(metrics.getReleased(), 1);
        Assert.assertEquals(worker().runCycle(), 0, "No row is claimed before the webhook's next attempt time.");
    }

    @Test
    public void testSuccessfulProbeSendsTheRestAndResetsEndpoint() throws Exception {

        store.insert(delivery("wh-1", "/hook").build());
        store.insert(delivery("wh-1", "/hook").build());
        queue.recordEndpointFailure("wh-1", "org-1", 503, n -> Duration.ZERO);

        worker().runCycle();

        Assert.assertEquals(endpoint.received().size(), 2);
        Assert.assertEquals(count("SELECT count(*) FROM webhook_delivery"), 0);
        Assert.assertEquals(count("SELECT consecutive_failures FROM webhook_endpoint_state"), 0);
    }

    @Test
    public void testPermanentFailure() throws Exception {

        endpoint.respond(request -> Reply.status(422));
        store.insert(delivery("wh-1", "/hook").build());

        worker().runCycle();

        Assert.assertEquals(count("SELECT count(*) FROM webhook_delivery WHERE status = 'FAILED'"
                + " AND attempt_count = 2 AND last_status_code = 422"), 1);
        Assert.assertEquals(count("SELECT consecutive_failures FROM webhook_endpoint_state"), 1,
                "A bad message is not an endpoint failure: only the failure in IS is counted.");
        Assert.assertEquals(metrics.getFailed(), 1);
    }

    @Test
    public void testRejectingEndpointWaitsAtLeastTheMinimum() throws Exception {

        endpoint.respond(request -> Reply.status(410));
        store.insert(delivery("wh-1", "/hook").build());

        worker().runCycle();

        Assert.assertEquals(count("SELECT count(*) FROM webhook_endpoint_state"
                + " WHERE next_attempt_at > now() + interval '290 seconds'"), 1);
    }

    @Test
    public void testRetryAfterIsRespected() throws Exception {

        endpoint.respond(request -> new Reply(429, "", 0, "120", null));
        store.insert(delivery("wh-1", "/hook").build());

        worker().runCycle();

        Assert.assertEquals(count("SELECT count(*) FROM webhook_endpoint_state WHERE next_attempt_at"
                + " BETWEEN now() + interval '110 seconds' AND now() + interval '130 seconds'"), 1);
    }

    @Test
    public void testRetryBeyondWindowExpires() throws Exception {

        endpoint.respond(request -> Reply.status(500));
        store.insert(delivery("wh-1", "/hook").retryWindow(Duration.ofSeconds(10)).build());

        worker().runCycle();

        Assert.assertEquals(count("SELECT count(*) FROM webhook_delivery WHERE status = 'EXPIRED'"), 1);
        Assert.assertEquals(metrics.getExpired(), 1);
    }

    @Test
    public void testUndecryptableRowFailsWithoutRequest() throws Exception {

        store.insert(delivery("wh-1", "/hook").build());
        PostgresTestSupport.execute("UPDATE webhook_delivery SET key_id = 'retired-key'");

        worker().runCycle();

        Assert.assertTrue(endpoint.received().isEmpty());
        Assert.assertEquals(count("SELECT count(*) FROM webhook_delivery WHERE status = 'FAILED'"
                + " AND attempt_count = 1"), 1, "No attempt is counted.");
    }

    @Test
    public void testBlockedAddressFailsWithoutRequest() throws Exception {

        store.insert(delivery("wh-1", "/hook").build());
        DeliveryHttpClient guarded = client(AddressPolicy.publicOnly());
        try {
            worker(sender(guarded)).runCycle();
        } finally {
            guarded.close();
        }

        Assert.assertTrue(endpoint.received().isEmpty());
        Assert.assertEquals(count("SELECT count(*) FROM webhook_delivery WHERE status = 'FAILED'"
                + " AND attempt_count = 1 AND last_error LIKE '%not allowed%'"), 1);
    }

    @Test
    public void testInvalidStoredEndpointFails() throws Exception {

        store.insert(delivery("wh-1", "/hook").endpointUrl("ftp://hooks.example.com/").build());

        worker().runCycle();

        Assert.assertEquals(count("SELECT count(*) FROM webhook_delivery WHERE status = 'FAILED'"), 1);
    }

    @Test
    public void testTimeoutIsRetried() throws Exception {

        endpoint.respond(request -> new Reply(200, "", 2000, null, null));
        store.insert(delivery("wh-1", "/hook").build());

        worker().runCycle();

        Assert.assertEquals(count("SELECT count(*) FROM webhook_delivery WHERE status = 'PENDING'"
                + " AND last_error = 'SocketTimeoutException' AND last_status_code IS NULL"), 1);
    }

    @Test
    public void testRowsPastSendDeadlineAreReleased() throws Exception {

        store.insert(delivery("wh-1", "/hook").build());
        store.insert(delivery("wh-2", "/hook").build());
        WebhookGroupSender sender = sender(client);

        sender.send(queue.claim("worker-a", 10, LEASE), Instant.now().minusSeconds(1));

        Assert.assertTrue(endpoint.received().isEmpty());
        Assert.assertEquals(count("SELECT count(*) FROM webhook_delivery WHERE status = 'PENDING'"
                + " AND attempt_count = 1"), 2);
        Assert.assertEquals(metrics.getReleased(), 2);
    }

    @Test
    public void testStoppingReleasesUnsentRows() throws Exception {

        store.insert(delivery("wh-1", "/hook").build());
        stopping.set(true);

        worker().runCycle();

        Assert.assertTrue(endpoint.received().isEmpty());
        Assert.assertEquals(count("SELECT count(*) FROM webhook_delivery WHERE status = 'PENDING'"), 1);
    }

    @Test
    public void testWebhooksAreSentInParallelGroups() throws Exception {

        for (int webhook = 0; webhook < 3; webhook++) {
            for (int row = 0; row < 2; row++) {
                store.insert(delivery("wh-" + webhook, "/hook").build());
            }
        }

        Assert.assertEquals(worker().runCycle(), 6);

        Assert.assertEquals(endpoint.received().size(), 6);
        Assert.assertEquals(count("SELECT count(*) FROM webhook_delivery"), 0);
    }

    @Test
    public void testResultAfterLostLeaseIsNotRecorded() throws Exception {

        endpoint.respond(request -> new Reply(200, "", 300, null, null));
        store.insert(delivery("wh-1", "/hook").build());
        WebhookGroupSender sender = sender(client);
        List<ClaimedDelivery> claimed = queue.claim("worker-a", 10, LEASE);
        // Another replica took over the row, as after a lease expiry.
        PostgresTestSupport.execute("UPDATE webhook_delivery SET locked_by = 'worker-b'");

        sender.send(claimed, Instant.now().plusSeconds(60));

        Assert.assertEquals(metrics.getLeaseLost(), 1);
        Assert.assertEquals(count("SELECT count(*) FROM webhook_delivery WHERE locked_by = 'worker-b'"), 1);
    }

    @Test
    public void testLoopDeliversAndStops() throws Exception {

        RetryWorker worker = worker();
        worker.start();
        try {
            store.insert(delivery("wh-1", "/hook").build());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (count("SELECT count(*) FROM webhook_delivery") > 0 && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            Assert.assertEquals(count("SELECT count(*) FROM webhook_delivery"), 0);
            Assert.assertTrue(worker.isAlive());
            Assert.assertTrue(worker.isReady());
        } finally {
            worker.stop(Duration.ofSeconds(2));
        }
        Assert.assertFalse(worker.isAlive());
    }

    @Test
    public void testPauseRules() {

        WebhookGroupSender sender = sender(client);

        Assert.assertEquals(sender.pause(1, DeliveryOutcome.RETRY, null), Duration.ofSeconds(30));
        Assert.assertEquals(sender.pause(3, DeliveryOutcome.RETRY, null), Duration.ofSeconds(120));
        Assert.assertEquals(sender.pause(1, DeliveryOutcome.ENDPOINT_REJECTED, null), Duration.ofMinutes(5));
        Assert.assertEquals(sender.pause(1, DeliveryOutcome.RETRY, Duration.ofSeconds(90)), Duration.ofSeconds(90));
        Assert.assertEquals(sender.pause(1, DeliveryOutcome.RETRY, Duration.ofDays(2)), Duration.ofHours(1),
                "Retry-After is capped at the maximum backoff.");
        Assert.assertEquals(sender.pause(3, DeliveryOutcome.RETRY, Duration.ofSeconds(10)), Duration.ofSeconds(120),
                "A shorter Retry-After does not shorten the backoff.");
    }

    @AfterClass
    public void resetLogLevel() {

        Logger.getLogger(LogSetup.APPLICATION_LOGGER).setLevel(null);
    }
}
