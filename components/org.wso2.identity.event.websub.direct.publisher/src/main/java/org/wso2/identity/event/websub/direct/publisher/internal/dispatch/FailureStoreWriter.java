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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.identity.event.delivery.core.store.DeliveryStatus;
import org.wso2.identity.event.delivery.core.store.DeliveryStore;
import org.wso2.identity.event.delivery.core.store.DeliveryStoreException;
import org.wso2.identity.event.delivery.core.store.NewDelivery;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Writes failed deliveries to the database on its own threads, with a bounded in-memory buffer.
 * <p>
 * If a write fails, for example during a database failover, the entry goes back into the buffer and is retried with
 * backoff until it is older than {@code maxAge}. An entry is dropped, with an ERROR log and a metric, when it ages
 * out or when the buffer is full. Callers never block.
 */
public class FailureStoreWriter {

    private static final Log LOG = LogFactory.getLog(FailureStoreWriter.class);
    private static final long POLL_MILLIS = 200;
    private static final long MAX_BACKOFF_MILLIS = 5000;

    /**
     * Writer settings.
     */
    public static final class Settings {

        private final int threads;
        private final int capacity;
        private final Duration maxAge;
        private final long maxPendingPerWebhook;
        private final Duration retryWindow;

        public Settings(int threads, int capacity, Duration maxAge, long maxPendingPerWebhook, Duration retryWindow) {

            this.threads = threads;
            this.capacity = capacity;
            this.maxAge = maxAge;
            this.maxPendingPerWebhook = maxPendingPerWebhook;
            this.retryWindow = retryWindow;
        }
    }

    private final DeliveryStore store;
    private final Settings settings;
    private final DeliveryObserver observer;
    private final DispatchMetrics metrics;
    private final LongSupplier nanoClock;
    private final BlockingQueue<Entry> queue;
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;

    public FailureStoreWriter(DeliveryStore store, Settings settings, DeliveryObserver observer,
                              DispatchMetrics metrics) {

        this(store, settings, observer, metrics, System::nanoTime);
    }

    FailureStoreWriter(DeliveryStore store, Settings settings, DeliveryObserver observer, DispatchMetrics metrics,
                       LongSupplier nanoClock) {

        this.store = store;
        this.settings = settings;
        this.observer = observer;
        this.metrics = metrics;
        this.nanoClock = nanoClock;
        this.queue = new ArrayBlockingQueue<>(settings.capacity);
    }

    /**
     * Starts the writer threads.
     */
    public synchronized void start() {

        if (running) {
            return;
        }
        running = true;
        for (int i = 0; i < settings.threads; i++) {
            Thread thread = new Thread(this::run, "websub-direct-store-writer-" + i);
            thread.setDaemon(true);
            thread.start();
            threads.add(thread);
        }
    }

    /**
     * Queues a delivery for the database.
     *
     * @param request     Delivery that was not delivered.
     * @param status      PENDING for a retryable failure, FAILED for a permanent one.
     * @param attempts    1 if a request was sent, 0 if not.
     * @param statusCode  HTTP status of the attempt, or null.
     * @param error       Short reason, without secrets or payload data.
     * @param retryDelay  Delay before the first retry.
     * @return False if the buffer is full and the delivery was dropped.
     */
    public boolean submit(DeliveryRequest request, DeliveryStatus status, int attempts, Integer statusCode,
                          String error, Duration retryDelay) {

        NewDelivery delivery;
        try {
            delivery = NewDelivery.builder()
                    .eventId(request.getEventId())
                    .tenantId(request.getTenantId())
                    .rootTenantId(request.getRootTenantId())
                    .webhookId(request.getWebhookId())
                    .eventType(request.getEventType())
                    .topic(request.getTopic())
                    .endpointUrl(request.getEndpointUrl())
                    .payload(request.getPayload())
                    .headers(request.getHeaders())
                    .status(status)
                    .attemptCount(attempts)
                    .firstRetryDelay(retryDelay)
                    .retryWindow(settings.retryWindow)
                    .lastStatusCode(statusCode)
                    .lastError(error)
                    .build();
        } catch (IllegalArgumentException e) {
            drop(request, "the delivery is invalid: " + e.getMessage());
            return false;
        }
        if (!queue.offer(new Entry(request, delivery, nanoClock.getAsLong()))) {
            drop(request, "the store buffer is full");
            return false;
        }
        return true;
    }

    /**
     * @return Entries waiting to be written.
     */
    public int buffered() {

        return queue.size();
    }

    /**
     * Stops accepting work and writes what is buffered, until the deadline.
     *
     * @param grace Time allowed to finish.
     */
    public void shutdown(Duration grace) {

        running = false;
        long deadline = nanoClock.getAsLong() + grace.toNanos();
        for (Thread thread : threads) {
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - nanoClock.getAsLong());
            if (remainingMillis <= 0) {
                break;
            }
            try {
                thread.join(remainingMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Entry left;
        while ((left = queue.poll()) != null) {
            drop(left.request, "IS shut down before the delivery could be stored");
        }
    }

    private void run() {

        while (running || !queue.isEmpty()) {
            Entry entry;
            try {
                entry = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (entry != null) {
                write(entry);
            }
        }
    }

    void write(Entry entry) {

        NewDelivery delivery = entry.delivery;
        try {
            if (delivery.getStatus() == DeliveryStatus.PENDING
                    && store.countPending(delivery.getWebhookId()) >= settings.maxPendingPerWebhook) {
                drop(entry.request, "the webhook has reached its limit of pending deliveries");
                return;
            }
            store.insert(delivery);
            metrics.stored(delivery.getStatus() == DeliveryStatus.PENDING);
            observer.onStored(entry.request, delivery.getStatus());
        } catch (DeliveryStoreException | RuntimeException e) {
            metrics.storeFailure();
            entry.failures++;
            if (nanoClock.getAsLong() - entry.queuedAt >= settings.maxAge.toNanos()) {
                LOG.error("Failed webhook delivery could not be stored within " + settings.maxAge.getSeconds()
                        + " seconds. Event: " + delivery.getEventId() + ", webhook: " + delivery.getWebhookId(), e);
                drop(entry.request, "the delivery database was unavailable");
                return;
            }
            LOG.warn("Failed webhook delivery could not be stored; retrying. Event: " + delivery.getEventId()
                    + ", webhook: " + delivery.getWebhookId() + ". " + e.getMessage());
            pause(Math.min(MAX_BACKOFF_MILLIS, 250L << Math.min(entry.failures, 5)));
            if (!queue.offer(entry)) {
                drop(entry.request, "the store buffer is full");
            }
        }
    }

    private void pause(long millis) {

        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void drop(DeliveryRequest request, String reason) {

        metrics.dropped();
        LOG.error("Webhook delivery dropped because " + reason + ". Event: " + request.getEventId() + ", webhook: "
                + request.getWebhookId() + ", organization: " + request.getTenantId());
        observer.onDropped(request, reason);
    }

    /**
     * A buffered delivery.
     */
    static final class Entry {

        private final DeliveryRequest request;
        private final NewDelivery delivery;
        private final long queuedAt;
        private int failures;

        Entry(DeliveryRequest request, NewDelivery delivery, long queuedAt) {

            this.request = request;
            this.delivery = delivery;
            this.queuedAt = queuedAt;
        }
    }
}
