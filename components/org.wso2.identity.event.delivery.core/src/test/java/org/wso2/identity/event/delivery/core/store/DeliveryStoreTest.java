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

import org.postgresql.ds.PGSimpleDataSource;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.wso2.identity.event.delivery.core.crypto.DeliveryCryptoException;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Tests for {@link DeliveryStore} against PostgreSQL, connected as the Identity Server role.
 */
public class DeliveryStoreTest {

    private DeliveryStore store;
    private DeliveryQueue queue;

    @BeforeClass
    public void startDatabase() throws SQLException {

        PostgresTestSupport.start();
        store = new DeliveryStore(PostgresTestSupport.isDataSource(), PostgresTestSupport.cipher());
        queue = new DeliveryQueue(PostgresTestSupport.workerDataSource(), PostgresTestSupport.cipher());
    }

    @BeforeMethod
    public void cleanTables() throws SQLException {

        PostgresTestSupport.truncate();
    }

    @Test
    public void testInsertStoresEncryptedPayloadAndHeaders() throws Exception {

        NewDelivery delivery = PostgresTestSupport.delivery("wh-1").build();

        Assert.assertTrue(store.insert(delivery));

        try (Connection connection = PostgresTestSupport.adminDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT payload, headers_enc, key_id, status, attempt_count, content_type, last_status_code,"
                             + " EXTRACT(EPOCH FROM expires_at - created_at) AS window_seconds FROM webhook_delivery");
             ResultSet resultSet = statement.executeQuery()) {
            Assert.assertTrue(resultSet.next());
            String storedPayload = new String(resultSet.getBytes("payload"), StandardCharsets.ISO_8859_1);
            Assert.assertFalse(storedPayload.contains("jti"), "The payload must be stored encrypted.");
            String storedHeaders = new String(resultSet.getBytes("headers_enc"), StandardCharsets.ISO_8859_1);
            Assert.assertFalse(storedHeaders.contains("sha256"), "The headers must be stored encrypted.");
            Assert.assertEquals(resultSet.getString("key_id"), "test-key");
            Assert.assertEquals(resultSet.getString("status"), "PENDING");
            Assert.assertEquals(resultSet.getInt("attempt_count"), 1);
            Assert.assertEquals(resultSet.getString("content_type"), "application/json");
            Assert.assertEquals(resultSet.getInt("last_status_code"), 503);
            Assert.assertEquals(resultSet.getLong("window_seconds"), 86400L);
        }

        List<ClaimedDelivery> claimed = queue.claim("worker-a", 10, Duration.ofMinutes(2));
        Assert.assertEquals(claimed.size(), 1);
        DeliveryContent content = queue.open(claimed.get(0));
        Assert.assertEquals(content.getPayload(), delivery.getPayload());
        Assert.assertEquals(content.getHeaders(), delivery.getHeaders());
    }

    @Test
    public void testFailedTryInIsSetsWebhookNextAttempt() throws Exception {

        store.insert(PostgresTestSupport.delivery("wh-1").firstRetryDelay(Duration.ofSeconds(30)).build());
        store.insert(PostgresTestSupport.delivery("wh-1").firstRetryDelay(Duration.ofSeconds(30))
                .lastStatusCode(502).build());

        Assert.assertEquals(query("SELECT consecutive_failures FROM webhook_endpoint_state"), 2L);
        Assert.assertEquals(query("SELECT last_failure_status_code FROM webhook_endpoint_state"), 502L);
        Assert.assertEquals(query("SELECT count(*) FROM webhook_endpoint_state WHERE next_attempt_at"
                + " BETWEEN now() + interval '25 seconds' AND now() + interval '31 seconds'"), 1L);
        Assert.assertEquals(query("SELECT count(*) FROM webhook_delivery WHERE last_attempt_at IS NOT NULL"), 2L);
    }

    @Test
    public void testFailedTryInIsKeepsLaterNextAttempt() throws Exception {

        queue.recordEndpointFailure("wh-1", "org-1", 503, n -> Duration.ofHours(1));

        store.insert(PostgresTestSupport.delivery("wh-1").firstRetryDelay(Duration.ofSeconds(30)).build());

        Assert.assertEquals(query("SELECT consecutive_failures FROM webhook_endpoint_state"), 2L);
        Assert.assertEquals(query("SELECT count(*) FROM webhook_endpoint_state WHERE next_attempt_at"
                + " > now() + interval '59 minutes'"), 1L, "The worker's longer wait must not be shortened.");
    }

    @Test
    public void testEventStoredWithoutTryLeavesEndpointStateUnchanged() throws Exception {

        store.insert(PostgresTestSupport.delivery("wh-1").attemptCount(0).build());
        store.insert(PostgresTestSupport.delivery("wh-2").status(DeliveryStatus.FAILED).lastStatusCode(400).build());

        Assert.assertEquals(query("SELECT count(*) FROM webhook_endpoint_state"), 0L,
                "Neither a stored-only event nor a final failure of one event counts against the endpoint.");
        Assert.assertEquals(query("SELECT count(*) FROM webhook_delivery WHERE last_attempt_at IS NULL"), 1L);
    }

    @Test
    public void testSameEventForSameWebhookIsStoredOnce() throws DeliveryStoreException {

        NewDelivery delivery = PostgresTestSupport.delivery("wh-1").build();

        Assert.assertTrue(store.insert(delivery));
        Assert.assertFalse(store.insert(delivery));
        Assert.assertEquals(store.countPending("wh-1"), 1);
    }

    @Test
    public void testSameEventForTwoWebhooksIsStoredTwice() throws DeliveryStoreException {

        NewDelivery first = PostgresTestSupport.delivery("wh-1").build();
        NewDelivery second = PostgresTestSupport.delivery("wh-2").eventId(first.getEventId()).build();

        Assert.assertTrue(store.insert(first));
        Assert.assertTrue(store.insert(second));
    }

    @Test
    public void testCountPendingIgnoresFinalRows() throws DeliveryStoreException {

        store.insert(PostgresTestSupport.delivery("wh-1").build());
        store.insert(PostgresTestSupport.delivery("wh-1").build());
        store.insert(PostgresTestSupport.delivery("wh-1").status(DeliveryStatus.FAILED).build());
        store.insert(PostgresTestSupport.delivery("wh-2").build());

        Assert.assertEquals(store.countPending("wh-1"), 2);
        Assert.assertEquals(store.countPending("wh-3"), 0);
    }

    @Test
    public void testCancelPendingByWebhook() throws DeliveryStoreException {

        store.insert(PostgresTestSupport.delivery("wh-1").build());
        store.insert(PostgresTestSupport.delivery("wh-1").build());
        store.insert(PostgresTestSupport.delivery("wh-2").build());
        // The oldest row, of wh-1, is being sent by a worker when the webhook is deactivated.
        List<ClaimedDelivery> claimed = queue.claim("worker-a", 1, Duration.ofMinutes(2));
        Assert.assertEquals(claimed.get(0).getWebhookId(), "wh-1");

        Assert.assertEquals(store.cancelPending("wh-1"), 2);
        Assert.assertEquals(store.countPending("wh-1"), 0);
        Assert.assertEquals(store.countPending("wh-2"), 1);
    }

    @Test
    public void testWorkerCannotOverwriteCancelledRow() throws DeliveryStoreException {

        store.insert(PostgresTestSupport.delivery("wh-1").build());
        ClaimedDelivery claimed = queue.claim("worker-a", 1, Duration.ofMinutes(2)).get(0);

        store.cancelPending("wh-1");

        Assert.assertFalse(queue.reschedule(claimed.getId(), "worker-a", Duration.ofSeconds(30), 503, "x")
                .isPresent());
        Assert.assertFalse(queue.markDelivered(claimed.getId(), "worker-a"));
    }

    @Test
    public void testCancelPendingByTopic() throws DeliveryStoreException {

        store.insert(PostgresTestSupport.delivery("wh-1").build());
        store.insert(PostgresTestSupport.delivery("wh-1").topic("myorg.org-1.schema.wso2.v1.event.session").build());

        Assert.assertEquals(store.cancelPending("wh-1", "myorg.org-1.schema.wso2.v1.event.session"), 1);
        Assert.assertEquals(store.countPending("wh-1"), 1);
    }

    @Test
    public void testCancelPendingByEndpointAndTopic() throws DeliveryStoreException {

        store.insert(PostgresTestSupport.delivery("wh-1").build());
        store.insert(PostgresTestSupport.delivery("wh-1").topic("myorg.org-1.schema.wso2.v1.event.session").build());
        store.insert(PostgresTestSupport.delivery("wh-2").build());

        Assert.assertEquals(store.cancelPendingByEndpoint("https://hooks.example.com/wh-1",
                "myorg.org-1.schema.wso2.v1.event.login"), 1);
        Assert.assertEquals(store.countPending("wh-1"), 1);
        Assert.assertEquals(store.countPending("wh-2"), 1);
    }

    @Test
    public void testDeleteByTenantKeepsOtherOwners() throws DeliveryStoreException {

        store.insert(PostgresTestSupport.delivery("wh-1").tenantId("sub-org").build());
        store.insert(PostgresTestSupport.delivery("wh-2").tenantId("org-1").build());

        Assert.assertEquals(store.deleteByTenant("sub-org"), 1);
        Assert.assertEquals(store.countPending("wh-2"), 1);
    }

    @Test
    public void testDeleteByRootTenant() throws DeliveryStoreException {

        store.insert(PostgresTestSupport.delivery("wh-1").tenantId("sub-org").rootTenantId("root-1").build());
        store.insert(PostgresTestSupport.delivery("wh-2").tenantId("org-1").rootTenantId("root-1").build());
        store.insert(PostgresTestSupport.delivery("wh-3").tenantId("other").rootTenantId("root-2").build());

        Assert.assertEquals(store.deleteByRootTenant("root-1"), 2);
        Assert.assertEquals(store.countPending("wh-3"), 1);
    }

    @Test
    public void testLongErrorIsTruncated() throws Exception {

        char[] longError = new char[3000];
        Arrays.fill(longError, 'e');
        store.insert(PostgresTestSupport.delivery("wh-1").lastError(new String(longError)).build());

        try (Connection connection = PostgresTestSupport.adminDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT length(last_error) FROM webhook_delivery");
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            Assert.assertEquals(resultSet.getInt(1), 1024);
        }
    }

    @Test(expectedExceptions = DeliveryCryptoException.class)
    public void testRowWithChangedKeyIdCannotBeOpened() throws Exception {

        store.insert(PostgresTestSupport.delivery("wh-1").build());
        PostgresTestSupport.execute("UPDATE webhook_delivery SET key_id = 'other-key'");

        queue.open(queue.claim("worker-a", 1, Duration.ofMinutes(2)).get(0));
    }

    @Test(expectedExceptions = DeliveryCryptoException.class)
    public void testPayloadCopiedFromAnotherRowCannotBeOpened() throws Exception {

        store.insert(PostgresTestSupport.delivery("wh-1").build());
        store.insert(PostgresTestSupport.delivery("wh-2").build());
        PostgresTestSupport.execute("UPDATE webhook_delivery SET payload = (SELECT payload FROM webhook_delivery"
                + " WHERE webhook_id = 'wh-2') WHERE webhook_id = 'wh-1'");

        List<ClaimedDelivery> claimed = queue.claim("worker-a", 2, Duration.ofMinutes(2));
        for (ClaimedDelivery delivery : claimed) {
            if (delivery.getWebhookId().equals("wh-1")) {
                queue.open(delivery);
            }
        }
    }

    @Test(expectedExceptions = DeliveryStoreException.class)
    public void testDatabaseErrorIsWrapped() throws DeliveryStoreException {

        DeliveryStore broken = new DeliveryStore(new PGSimpleDataSource() {

            private static final long serialVersionUID = 1L;

            @Override
            public Connection getConnection() throws SQLException {

                throw new SQLException("database unavailable");
            }
        }, PostgresTestSupport.cipher());

        broken.insert(PostgresTestSupport.delivery("wh-1").build());
    }

    private static long query(String sql) throws SQLException {

        try (Connection connection = PostgresTestSupport.adminDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }
}
