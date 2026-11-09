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

import org.postgresql.ds.PGSimpleDataSource;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.wso2.identity.event.delivery.core.store.DeliveryQueue;
import org.wso2.identity.event.delivery.core.store.DeliveryStatus;
import org.wso2.identity.event.delivery.core.store.DeliveryStore;
import org.wso2.identity.event.delivery.core.store.PostgresTestSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tests for {@link Housekeeper} against PostgreSQL.
 */
public class HousekeeperTest {

    private DeliveryStore store;
    private DeliveryQueue queue;
    private WorkerMetrics metrics;
    private Housekeeper housekeeper;

    @BeforeClass
    public void startDatabase() throws SQLException {

        // Debug on, so the debug messages are built and checked for errors; they go to no handler here.
        Logger.getLogger(LogSetup.APPLICATION_LOGGER).setLevel(Level.FINE);
        PostgresTestSupport.start();
        store = new DeliveryStore(PostgresTestSupport.isDataSource(), PostgresTestSupport.cipher());
        queue = new DeliveryQueue(PostgresTestSupport.workerDataSource(), PostgresTestSupport.cipher());
    }

    @BeforeMethod
    public void setUp() throws SQLException {

        PostgresTestSupport.truncate();
        metrics = new WorkerMetrics();
        housekeeper = new Housekeeper(queue, metrics, new Housekeeper.Settings(Duration.ofSeconds(30),
                Duration.ofSeconds(60), Duration.ofMinutes(10), Duration.ofSeconds(15), Duration.ofDays(7), 2));
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
    public void testReapLeases() throws Exception {

        store.insert(PostgresTestSupport.delivery("wh-1").build());
        queue.claim("worker-a", 1, Duration.ofMillis(1));
        Thread.sleep(20);

        housekeeper.reapLeases();

        Assert.assertEquals(metrics.getLeasesRecovered(), 1);
        Assert.assertEquals(count("SELECT count(*) FROM webhook_delivery WHERE status = 'PENDING'"), 1);
    }

    @Test
    public void testExpireOverdueWorksInBatches() throws Exception {

        for (int i = 0; i < 5; i++) {
            store.insert(PostgresTestSupport.delivery("wh-1").retryWindow(Duration.ofMillis(1)).build());
        }
        Thread.sleep(20);

        housekeeper.expireOverdue();

        Assert.assertEquals(metrics.getExpired(), 5, "Batches of 2 continue until all are expired.");
    }

    @Test
    public void testPurge() throws Exception {

        for (int i = 0; i < 3; i++) {
            store.insert(PostgresTestSupport.delivery("wh-1").status(DeliveryStatus.FAILED).build());
        }
        queue.recordEndpointFailure("wh-idle", "org-1", 503, n -> Duration.ofSeconds(1));
        PostgresTestSupport.execute("UPDATE webhook_delivery SET updated_at = now() - interval '8 days'");
        PostgresTestSupport.execute("UPDATE webhook_endpoint_state SET updated_at = now() - interval '8 days'");

        housekeeper.purge();

        Assert.assertEquals(count("SELECT count(*) FROM webhook_delivery"), 0);
        Assert.assertEquals(count("SELECT count(*) FROM webhook_endpoint_state"), 0);
    }

    @Test
    public void testStatsReachMetrics() throws Exception {

        store.insert(PostgresTestSupport.delivery("wh-1").build());

        housekeeper.refreshStats();

        String text = metrics.toPrometheusText();
        Assert.assertTrue(text.contains("webhook_pending 1\n"), text);
        Assert.assertTrue(text.contains("webhook_pending_due 1\n"), text);
    }

    @Test
    public void testDatabaseErrorDoesNotEscape() {

        PGSimpleDataSource broken = new PGSimpleDataSource();
        broken.setUrl("jdbc:postgresql://127.0.0.1:1/none");
        Housekeeper failing = new Housekeeper(new DeliveryQueue(broken, PostgresTestSupport.cipher()), metrics,
                new Housekeeper.Settings(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1),
                        Duration.ofSeconds(1), Duration.ofDays(7), 10));

        failing.reapLeases();
        failing.expireOverdue();
        failing.purge();
        failing.refreshStats();
    }

    @Test
    public void testStartAndStop() throws Exception {

        Housekeeper scheduled = new Housekeeper(queue, metrics, new Housekeeper.Settings(Duration.ofMillis(20),
                Duration.ofMillis(20), Duration.ofMillis(20), Duration.ofMillis(20), Duration.ofDays(7), 10));
        store.insert(PostgresTestSupport.delivery("wh-1").build());

        scheduled.start();
        try {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!metrics.toPrometheusText().contains("webhook_pending 1") && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            Assert.assertTrue(metrics.toPrometheusText().contains("webhook_pending 1"));
        } finally {
            scheduled.stop();
        }
    }

    @AfterClass
    public void resetLogLevel() {

        Logger.getLogger(LogSetup.APPLICATION_LOGGER).setLevel(null);
    }
}
