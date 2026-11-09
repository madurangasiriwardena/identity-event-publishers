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

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Tests for {@link DeliveryQueue} against PostgreSQL, connected as the Retry Worker role.
 */
public class DeliveryQueueTest {

    private static final Duration LEASE = Duration.ofMinutes(2);

    private DeliveryStore store;
    private DeliveryQueue queue;

    @BeforeClass
    public void startDatabase() throws SQLException {

        PostgresTestSupport.start();
        store = new DeliveryStore(PostgresTestSupport.isDataSource());
        queue = new DeliveryQueue(PostgresTestSupport.workerDataSource());
    }

    @BeforeMethod
    public void cleanTables() throws SQLException {

        PostgresTestSupport.truncate();
    }

    @Test
    public void testClaimReturnsOnlyDueRowsOldestFirst() throws Exception {

        store.insert(PostgresTestSupport.delivery("wh-late").eventId("e-late").firstRetryDelay(Duration.ofHours(1))
                .build());
        store.insert(PostgresTestSupport.delivery("wh-1").eventId("e-1").build());
        store.insert(PostgresTestSupport.delivery("wh-2").eventId("e-2").build());
        store.insert(PostgresTestSupport.delivery("wh-3").eventId("e-failed").status(DeliveryStatus.FAILED).build());

        List<ClaimedDelivery> claimed = queue.claim("worker-a", 10, LEASE);

        Assert.assertEquals(eventIds(claimed), Arrays.asList("e-1", "e-2"));
        ClaimedDelivery first = claimed.get(0);
        Assert.assertEquals(first.getTenantId(), "org-1");
        Assert.assertEquals(first.getAttemptCount(), 1);
        Assert.assertEquals(first.getEndpointFailures(), 1, "The failed try in IS is counted.");
        Assert.assertEquals(first.getEndpointUrl(), "https://hooks.example.com/wh-1");
        Assert.assertTrue(first.getEventType().endsWith("loginSuccess"));
        Assert.assertNotNull(first.getExpiresAt());
        Assert.assertTrue(queue.claim("worker-b", 10, LEASE).isEmpty(), "Claimed rows must not be claimed twice.");
    }

    @Test
    public void testClaimRespectsBatchSize() throws Exception {

        for (int i = 0; i < 5; i++) {
            store.insert(PostgresTestSupport.delivery("wh-1").build());
        }

        Assert.assertEquals(queue.claim("worker-a", 3, LEASE).size(), 3);
        Assert.assertEquals(queue.claim("worker-a", 3, LEASE).size(), 2);
    }

    @Test
    public void testConcurrentClaimsNeverOverlap() throws Exception {

        int rows = 200;
        for (int i = 0; i < rows; i++) {
            store.insert(PostgresTestSupport.delivery("wh-" + (i % 7)).build());
        }
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<List<UUID>>> results = new ArrayList<>();
            for (int w = 0; w < 4; w++) {
                String workerId = "worker-" + w;
                results.add(executor.submit(new Callable<List<UUID>>() {
                    @Override
                    public List<UUID> call() throws Exception {

                        List<UUID> ids = new ArrayList<>();
                        List<ClaimedDelivery> batch;
                        do {
                            batch = queue.claim(workerId, 7, LEASE);
                            for (ClaimedDelivery delivery : batch) {
                                ids.add(delivery.getId());
                            }
                        } while (!batch.isEmpty());
                        return ids;
                    }
                }));
            }
            List<UUID> all = new ArrayList<>();
            for (Future<List<UUID>> result : results) {
                all.addAll(result.get());
            }
            Assert.assertEquals(all.size(), rows, "Every row must be claimed exactly once.");
            Assert.assertEquals(new HashSet<>(all).size(), rows, "No row may be claimed by two workers.");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void testMarkDeliveredDeletesRowOnlyForLeaseHolder() throws Exception {

        store.insert(PostgresTestSupport.delivery("wh-1").build());
        ClaimedDelivery claimed = queue.claim("worker-a", 1, LEASE).get(0);

        Assert.assertFalse(queue.markDelivered(claimed.getId(), "worker-b"));
        Assert.assertTrue(queue.markDelivered(claimed.getId(), "worker-a"));
        Assert.assertEquals(count("SELECT count(*) FROM webhook_delivery"), 0);
    }

    @Test
    public void testRescheduleCountsAttemptAndRecordsTime() throws Exception {

        store.insert(PostgresTestSupport.delivery("wh-1").build());
        PostgresTestSupport.execute("UPDATE webhook_delivery SET last_attempt_at = now() - interval '1 hour'");
        ClaimedDelivery claimed = queue.claim("worker-a", 1, LEASE).get(0);

        Optional<DeliveryStatus> status = queue.reschedule(claimed.getId(), "worker-a", Duration.ofMinutes(5), 502,
                "Bad Gateway");

        Assert.assertEquals(status, Optional.of(DeliveryStatus.PENDING));
        Assert.assertEquals(count("SELECT attempt_count FROM webhook_delivery"), 2);
        Assert.assertEquals(count("SELECT last_status_code FROM webhook_delivery"), 502);
        Assert.assertEquals(count("SELECT count(*) FROM webhook_delivery WHERE locked_by IS NULL"
                + " AND last_attempt_at > now() - interval '1 minute'"), 1);
        Assert.assertEquals(queue.claim("worker-a", 1, LEASE).size(), 1,
                "The row has no time of its own: it waits only for its webhook's next attempt time.");
    }

    @Test
    public void testWebhookNextAttemptHoldsBackAllItsRows() throws Exception {

        store.insert(PostgresTestSupport.delivery("wh-1").build());
        store.insert(PostgresTestSupport.delivery("wh-1").build());

        queue.recordEndpointFailure("wh-1", "org-1", 503, n -> Duration.ofMinutes(5));

        Assert.assertTrue(queue.claim("worker-a", 10, LEASE).isEmpty());
        Assert.assertEquals(count("SELECT count(*) FROM webhook_endpoint_state WHERE next_attempt_at"
                + " > now() + interval '4 minutes'"), 1);
    }

    @Test
    public void testReleasedRowGoesBeforeRowThatWasJustTried() throws Exception {

        store.insert(PostgresTestSupport.delivery("wh-1").eventId("e-1").build());
        store.insert(PostgresTestSupport.delivery("wh-1").eventId("e-2").build());
        PostgresTestSupport.execute("UPDATE webhook_delivery SET last_attempt_at = now() - interval '1 minute'"
                + " WHERE event_id = 'e-1'");
        List<ClaimedDelivery> claimed = queue.claim("worker-a", 10, LEASE);
        Assert.assertEquals(eventIds(claimed), Arrays.asList("e-1", "e-2"));

        // The probe e-1 fails; e-2 is released unsent.
        queue.reschedule(claimed.get(0).getId(), "worker-a", Duration.ZERO, 503, "Service Unavailable");
        queue.release(claimed.get(1).getId(), "worker-a");

        Assert.assertEquals(eventIds(queue.claim("worker-a", 10, LEASE)), Arrays.asList("e-2", "e-1"),
                "The rows take turns as the probe.");
    }

    @Test
    public void testRescheduleBeyondWindowExpiresRow() throws Exception {

        store.insert(PostgresTestSupport.delivery("wh-1").retryWindow(Duration.ofMinutes(10)).build());
        ClaimedDelivery claimed = queue.claim("worker-a", 1, LEASE).get(0);

        Assert.assertEquals(queue.reschedule(claimed.getId(), "worker-a", Duration.ofMinutes(30), null, "timeout"),
                Optional.of(DeliveryStatus.EXPIRED));
    }

    @Test
    public void testRescheduleWithoutLeaseChangesNothing() throws Exception {

        store.insert(PostgresTestSupport.delivery("wh-1").build());
        ClaimedDelivery claimed = queue.claim("worker-a", 1, LEASE).get(0);

        Assert.assertFalse(queue.reschedule(claimed.getId(), "worker-b", Duration.ofMinutes(1), 500, "x")
                .isPresent());
        Assert.assertEquals(count("SELECT count(*) FROM webhook_delivery WHERE status = 'IN_PROGRESS'"), 1);
    }

    @Test
    public void testReleaseDoesNotCountAttempt() throws Exception {

        store.insert(PostgresTestSupport.delivery("wh-1").build());
        ClaimedDelivery claimed = queue.claim("worker-a", 1, LEASE).get(0);

        Assert.assertTrue(queue.release(claimed.getId(), "worker-a"));
        Assert.assertEquals(count("SELECT attempt_count FROM webhook_delivery"), 1);
        Assert.assertEquals(queue.claim("worker-a", 1, LEASE).size(), 1, "A released row is due again.");
    }

    @Test
    public void testMarkFailed() throws Exception {

        store.insert(PostgresTestSupport.delivery("wh-1").build());
        store.insert(PostgresTestSupport.delivery("wh-2").build());
        List<ClaimedDelivery> claimed = queue.claim("worker-a", 2, LEASE);

        Assert.assertTrue(queue.markFailed(claimed.get(0).getId(), "worker-a", true, 422, "Unprocessable"));
        Assert.assertTrue(queue.markFailed(claimed.get(1).getId(), "worker-a", false, null, "cannot be read"));
        Assert.assertFalse(queue.markFailed(claimed.get(1).getId(), "worker-a", true, null, "again"));
        Assert.assertEquals(count("SELECT count(*) FROM webhook_delivery WHERE status = 'FAILED'"), 2);
        Assert.assertEquals(count("SELECT sum(attempt_count) FROM webhook_delivery"), 3);
    }

    @Test
    public void testExpiredLeaseIsReleased() throws Exception {

        store.insert(PostgresTestSupport.delivery("wh-1").build());
        ClaimedDelivery claimed = queue.claim("worker-a", 1, Duration.ofMillis(1)).get(0);
        Thread.sleep(20);

        Assert.assertEquals(queue.releaseExpiredLeases(), 1);
        Assert.assertFalse(queue.markDelivered(claimed.getId(), "worker-a"), "The old lease holder lost the row.");
        Assert.assertEquals(queue.claim("worker-b", 1, LEASE).size(), 1);
    }

    @Test
    public void testActiveLeaseIsNotReleased() throws Exception {

        store.insert(PostgresTestSupport.delivery("wh-1").build());
        queue.claim("worker-a", 1, LEASE);

        Assert.assertEquals(queue.releaseExpiredLeases(), 0);
    }

    @Test
    public void testWebhookIsSkippedUntilItsNextAttempt() throws Exception {

        store.insert(PostgresTestSupport.delivery("wh-1").build());
        store.insert(PostgresTestSupport.delivery("wh-2").build());

        int failures = queue.recordEndpointFailure("wh-1", "org-1", 503, n -> Duration.ofMinutes(n));

        Assert.assertEquals(failures, 2, "The failure in IS and this one.");
        List<ClaimedDelivery> claimed = queue.claim("worker-a", 10, LEASE);
        Assert.assertEquals(claimed.size(), 1);
        Assert.assertEquals(claimed.get(0).getWebhookId(), "wh-2");

        PostgresTestSupport.execute("UPDATE webhook_endpoint_state SET next_attempt_at = now() - interval '1 second'");
        List<ClaimedDelivery> probe = queue.claim("worker-a", 10, LEASE);
        Assert.assertEquals(probe.size(), 1);
        Assert.assertEquals(probe.get(0).getEndpointFailures(), 2, "The worker must see the endpoint was failing.");
    }

    @Test
    public void testEndpointFailuresAccumulateAndSuccessResets() throws Exception {

        List<Integer> seen = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            queue.recordEndpointFailure("wh-1", "org-1", 500, n -> {
                seen.add(n);
                return Duration.ofSeconds(30L * n);
            });
        }

        Assert.assertEquals(seen, Arrays.asList(1, 2, 3));
        Assert.assertEquals(count("SELECT count(*) FROM webhook_endpoint_state WHERE next_attempt_at"
                + " BETWEEN now() + interval '85 seconds' AND now() + interval '95 seconds'"), 1);

        queue.recordEndpointSuccess("wh-1");

        Assert.assertEquals(count("SELECT consecutive_failures FROM webhook_endpoint_state"), 0);
        Assert.assertEquals(count("SELECT count(*) FROM webhook_endpoint_state WHERE next_attempt_at IS NULL"
                + " AND last_success_at IS NOT NULL"), 1);
        Assert.assertEquals(count("SELECT last_failure_status_code FROM webhook_endpoint_state"), 500,
                "A success keeps the status and time of the last failure.");
        Assert.assertEquals(count("SELECT count(*) FROM webhook_endpoint_state WHERE last_failure_at IS NOT NULL"), 1);
    }

    @Test
    public void testEndpointFailureRollsBackWhenPauseCannotBeComputed() throws Exception {

        try {
            queue.recordEndpointFailure("wh-1", "org-1", 500, n -> {
                throw new IllegalStateException("broken backoff");
            });
            Assert.fail("The callback error must propagate.");
        } catch (IllegalStateException e) {
            Assert.assertEquals(count("SELECT count(*) FROM webhook_endpoint_state"), 0);
        }
    }

    @Test
    public void testSuccessForHealthyEndpointAddsNoState() throws Exception {

        queue.recordEndpointSuccess("wh-healthy");

        Assert.assertEquals(count("SELECT count(*) FROM webhook_endpoint_state"), 0);
    }

    @Test
    public void testExpireOverdueEndsRowsOfWaitingWebhook() throws Exception {

        store.insert(PostgresTestSupport.delivery("wh-1").retryWindow(Duration.ofMillis(1)).build());
        store.insert(PostgresTestSupport.delivery("wh-1").build());
        queue.recordEndpointFailure("wh-1", "org-1", 503, n -> Duration.ofHours(1));
        Thread.sleep(20);

        Assert.assertEquals(queue.expireOverdue(100), 1);
        Assert.assertEquals(count("SELECT count(*) FROM webhook_delivery WHERE status = 'EXPIRED'"), 1);
        Assert.assertEquals(count("SELECT count(*) FROM webhook_delivery WHERE status = 'PENDING'"), 1);
    }

    @Test
    public void testPurgeRemovesOnlyOldFinalRows() throws Exception {

        store.insert(PostgresTestSupport.delivery("wh-1").status(DeliveryStatus.FAILED).build());
        store.insert(PostgresTestSupport.delivery("wh-1").status(DeliveryStatus.FAILED).build());
        store.insert(PostgresTestSupport.delivery("wh-1").build());
        PostgresTestSupport.execute("UPDATE webhook_delivery SET updated_at = now() - interval '8 days'");
        store.insert(PostgresTestSupport.delivery("wh-1").status(DeliveryStatus.FAILED).build());

        Assert.assertEquals(queue.purgeTerminal(Duration.ofDays(7), 1), 1, "The limit must be respected.");
        Assert.assertEquals(queue.purgeTerminal(Duration.ofDays(7), 100), 1);
        Assert.assertEquals(count("SELECT count(*) FROM webhook_delivery"), 2,
                "The pending row and the recent failure stay.");
    }

    @Test
    public void testPurgeEndpointStatesKeepsStatesWithPendingWork() throws Exception {

        store.insert(PostgresTestSupport.delivery("wh-busy").build());
        queue.recordEndpointFailure("wh-busy", "org-1", 503, n -> Duration.ofMinutes(1));
        queue.recordEndpointFailure("wh-idle", "org-1", 503, n -> Duration.ofMinutes(1));
        PostgresTestSupport.execute("UPDATE webhook_endpoint_state SET updated_at = now() - interval '8 days'");

        Assert.assertEquals(queue.purgeEndpointStates(Duration.ofDays(7)), 1);
        Assert.assertEquals(count("SELECT count(*) FROM webhook_endpoint_state WHERE webhook_id = 'wh-busy'"), 1);
    }

    @Test
    public void testStats() throws Exception {

        store.insert(PostgresTestSupport.delivery("wh-1").build());
        store.insert(PostgresTestSupport.delivery("wh-1").build());
        store.insert(PostgresTestSupport.delivery("wh-2").firstRetryDelay(Duration.ofHours(1)).build());
        queue.claim("worker-a", 1, LEASE);
        PostgresTestSupport.execute("UPDATE webhook_delivery SET created_at = now() - interval '2 hours'"
                + " WHERE status = 'PENDING'");

        QueueStats stats = queue.stats();

        Assert.assertEquals(stats.getPending(), 2);
        Assert.assertEquals(stats.getDue(), 1);
        Assert.assertEquals(stats.getInProgress(), 1);
        Assert.assertTrue(stats.getOldestPendingAge().compareTo(Duration.ofMinutes(119)) > 0);
    }

    @Test
    public void testStatsOfEmptyQueue() throws Exception {

        QueueStats stats = queue.stats();

        Assert.assertEquals(stats.getPending(), 0);
        Assert.assertEquals(stats.getOldestPendingAge(), Duration.ZERO);
    }

    private static List<String> eventIds(List<ClaimedDelivery> claimed) {

        List<String> ids = new ArrayList<>();
        for (ClaimedDelivery delivery : claimed) {
            ids.add(delivery.getEventId());
        }
        return Collections.unmodifiableList(ids);
    }

    private static long count(String sql) throws SQLException {

        try (Connection connection = PostgresTestSupport.adminDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }
}
