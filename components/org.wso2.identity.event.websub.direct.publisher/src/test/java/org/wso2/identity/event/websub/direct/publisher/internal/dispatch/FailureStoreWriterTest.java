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

import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.wso2.identity.event.delivery.core.store.DeliveryStatus;
import org.wso2.identity.event.delivery.core.store.DeliveryStore;
import org.wso2.identity.event.delivery.core.store.DeliveryStoreException;
import org.wso2.identity.event.delivery.core.store.NewDelivery;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link FailureStoreWriter}.
 */
public class FailureStoreWriterTest {

    private DeliveryStore store;
    private DeliveryObserver observer;
    private DispatchMetrics metrics;
    private AtomicLong clock;

    @BeforeMethod
    public void setUp() {

        store = mock(DeliveryStore.class);
        observer = mock(DeliveryObserver.class);
        metrics = new DispatchMetrics();
        clock = new AtomicLong(1_000_000_000L);
    }

    private FailureStoreWriter writer(int capacity, long maxPending) {

        return new FailureStoreWriter(store, new FailureStoreWriter.Settings(1, capacity, Duration.ofSeconds(180),
                maxPending, Duration.ofHours(24)), observer, metrics, clock::get);
    }

    static DeliveryRequest request() {

        return DeliveryRequest.builder()
                .eventTenantDomain("myorg").tenantId("org-1").rootTenantId("org-1").webhookId("wh-1")
                .eventId("e-1").eventType("type").topic("topic").endpointUrl("https://hooks.example.com/")
                .payload(new byte[]{1}).headers(Collections.singletonMap("Content-Type", "application/json"))
                .build();
    }

    private static DeliveryStoreException databaseDown() {

        return new DeliveryStoreException("down", new SQLException("connection refused"));
    }

    @Test
    public void testStoresDelivery() throws Exception {

        when(store.insert(any(NewDelivery.class))).thenReturn(true);
        FailureStoreWriter writer = writer(10, 100);
        writer.start();
        writer.start();

        Assert.assertTrue(writer.submit(request(), DeliveryStatus.PENDING, 1, 503, "HTTP 503", Duration.ofSeconds(30)));

        verify(store, timeout(5000)).insert(any(NewDelivery.class));
        verify(observer, timeout(5000)).onStored(any(DeliveryRequest.class), eq(DeliveryStatus.PENDING));
        writer.shutdown(Duration.ofSeconds(1));
        Assert.assertEquals(metrics.getStoredPending(), 1);
    }

    @Test
    public void testRetriesWhileDatabaseIsDown() throws Exception {

        when(store.insert(any(NewDelivery.class))).thenThrow(databaseDown()).thenReturn(true);
        FailureStoreWriter writer = writer(10, 100);
        writer.start();

        writer.submit(request(), DeliveryStatus.PENDING, 1, 503, "HTTP 503", Duration.ofSeconds(30));

        verify(store, timeout(5000).times(2)).insert(any(NewDelivery.class));
        verify(observer, timeout(5000)).onStored(any(DeliveryRequest.class), eq(DeliveryStatus.PENDING));
        verify(observer, never()).onDropped(any(DeliveryRequest.class), anyString());
        writer.shutdown(Duration.ofSeconds(1));
        Assert.assertEquals(metrics.getStoreFailures(), 1);
    }

    @Test
    public void testDropsAfterMaxAge() throws Exception {

        when(store.insert(any(NewDelivery.class))).thenThrow(databaseDown());
        FailureStoreWriter writer = writer(10, 100);
        writer.submit(request(), DeliveryStatus.FAILED, 1, 400, "HTTP 400", Duration.ofSeconds(30));
        clock.addAndGet(Duration.ofSeconds(181).toNanos());

        writer.start();

        verify(observer, timeout(5000)).onDropped(any(DeliveryRequest.class), anyString());
        writer.shutdown(Duration.ofSeconds(1));
        Assert.assertEquals(metrics.getDropped(), 1);
    }

    @Test
    public void testDropsAboveWebhookPendingLimit() throws Exception {

        when(store.countPending("wh-1")).thenReturn(5L);
        FailureStoreWriter writer = writer(10, 5);
        writer.start();

        writer.submit(request(), DeliveryStatus.PENDING, 1, 503, "HTTP 503", Duration.ofSeconds(30));

        verify(observer, timeout(5000)).onDropped(any(DeliveryRequest.class), anyString());
        verify(store, never()).insert(any(NewDelivery.class));
        writer.shutdown(Duration.ofSeconds(1));
    }

    @Test
    public void testPendingLimitDoesNotApplyToFinalFailures() throws Exception {

        when(store.countPending("wh-1")).thenReturn(5L);
        when(store.insert(any(NewDelivery.class))).thenReturn(true);
        FailureStoreWriter writer = writer(10, 5);
        writer.start();

        writer.submit(request(), DeliveryStatus.FAILED, 1, 400, "HTTP 400", Duration.ofSeconds(30));

        verify(store, timeout(5000)).insert(any(NewDelivery.class));
        verify(store, never()).countPending(anyString());
        writer.shutdown(Duration.ofSeconds(1));
        Assert.assertEquals(metrics.getStoredFailed(), 1);
    }

    @Test
    public void testFullBufferDropsNewDelivery() {

        FailureStoreWriter writer = writer(1, 100);

        Assert.assertTrue(writer.submit(request(), DeliveryStatus.PENDING, 0, null, "x", Duration.ZERO));
        Assert.assertFalse(writer.submit(request(), DeliveryStatus.PENDING, 0, null, "x", Duration.ZERO));

        Assert.assertEquals(writer.buffered(), 1);
        verify(observer, times(1)).onDropped(any(DeliveryRequest.class), anyString());
    }

    @Test
    public void testInvalidDeliveryIsDropped() {

        DeliveryRequest withoutEventId = DeliveryRequest.builder().tenantId("org-1").rootTenantId("org-1")
                .webhookId("wh-1").eventType("type").topic("topic").endpointUrl("https://x/")
                .payload(new byte[]{1}).headers(Collections.<String, String>emptyMap()).build();

        Assert.assertFalse(writer(10, 100).submit(withoutEventId, DeliveryStatus.PENDING, 0, null, "x",
                Duration.ZERO));
        Assert.assertEquals(metrics.getDropped(), 1);
    }

    @Test
    public void testShutdownDropsWhatCannotBeWritten() {

        FailureStoreWriter writer = writer(10, 100);
        writer.submit(request(), DeliveryStatus.PENDING, 0, null, "x", Duration.ZERO);

        writer.shutdown(Duration.ZERO);

        Assert.assertEquals(writer.buffered(), 0);
        verify(observer).onDropped(any(DeliveryRequest.class), anyString());
    }
}
