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

import org.wso2.identity.event.delivery.core.store.ClaimedDelivery;
import org.wso2.identity.event.delivery.core.store.DeliveryQueue;
import org.wso2.identity.event.delivery.core.store.DeliveryStoreException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The claim loop of one replica (design section 5.2).
 * <p>
 * Each cycle claims a batch of due rows with SKIP LOCKED, groups them by webhook, and sends the groups in parallel,
 * each group in order. The next claim waits until the batch is finished, so a replica never holds more rows than
 * one batch. A full batch is followed by a short pause, an empty or partial one by a longer pause.
 */
public final class RetryWorker {

    private static final Logger LOG = Logger.getLogger(RetryWorker.class.getName());
    // Time kept free at the end of a lease to record the result of the last attempt.
    private static final Duration RECORD_MARGIN = Duration.ofSeconds(5);

    /**
     * Loop settings.
     */
    public static final class Settings {

        private final String workerId;
        private final int batchSize;
        private final int concurrency;
        private final Duration lease;
        private final Duration readTimeout;
        private final Duration busyPollInterval;
        private final Duration idlePollInterval;

        public Settings(String workerId, int batchSize, int concurrency, Duration lease, Duration readTimeout,
                        Duration busyPollInterval, Duration idlePollInterval) {

            this.workerId = workerId;
            this.batchSize = batchSize;
            this.concurrency = concurrency;
            this.lease = lease;
            this.readTimeout = readTimeout;
            this.busyPollInterval = busyPollInterval;
            this.idlePollInterval = idlePollInterval;
        }
    }

    private final DeliveryQueue queue;
    private final WebhookGroupSender sender;
    private final WorkerMetrics metrics;
    private final Settings settings;
    private final Clock clock;
    private final AtomicBoolean stopping;
    private final ExecutorService senders;
    private final AtomicLong lastCycleMillis = new AtomicLong();
    private final AtomicLong consecutivePollErrors = new AtomicLong();
    private volatile Thread poller;

    RetryWorker(DeliveryQueue queue, WebhookGroupSender sender, WorkerMetrics metrics, Settings settings,
                Clock clock, AtomicBoolean stopping) {

        this.queue = queue;
        this.sender = sender;
        this.metrics = metrics;
        this.settings = settings;
        this.clock = clock;
        this.stopping = stopping;
        this.senders = Executors.newFixedThreadPool(settings.concurrency, runnable -> {
            Thread thread = new Thread(runnable, "webhook-sender");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Starts the claim loop on its own thread.
     */
    public void start() {

        lastCycleMillis.set(clock.millis());
        poller = new Thread(this::loop, "webhook-retry-poller");
        poller.setDaemon(true);
        poller.start();
    }

    /**
     * Stops claiming, lets the current batch finish or release its unsent rows, and waits up to the grace period.
     *
     * @param grace Time allowed for the current batch.
     */
    public void stop(Duration grace) {

        stopping.set(true);
        Thread current = poller;
        if (current != null) {
            // Wakes the poller from its pause or from waiting on the batch; senders see the flag and release
            // their unsent rows.
            current.interrupt();
        }
        senders.shutdown();
        try {
            if (!senders.awaitTermination(grace.toMillis(), TimeUnit.MILLISECONDS)) {
                // Rows whose result is not recorded go back to PENDING when their lease expires.
                senders.shutdownNow();
            }
            if (current != null) {
                current.join(TimeUnit.SECONDS.toMillis(1));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * @return True while the loop runs and has completed a cycle recently. Used by the liveness probe.
     */
    public boolean isAlive() {

        Thread current = poller;
        long maxCycle = settings.lease.plus(settings.idlePollInterval).multipliedBy(2).toMillis();
        return current != null && current.isAlive() && clock.millis() - lastCycleMillis.get() < maxCycle;
    }

    /**
     * @return True if the last claims reached the database. Used by the readiness probe.
     */
    public boolean isReady() {

        return isAlive() && consecutivePollErrors.get() < 3;
    }

    private void loop() {

        while (!stopping.get()) {
            Duration pause = settings.idlePollInterval;
            try {
                int claimed = runCycle();
                consecutivePollErrors.set(0);
                if (claimed >= settings.batchSize) {
                    pause = settings.busyPollInterval;
                }
            } catch (DeliveryStoreException | RuntimeException e) {
                metrics.pollError();
                consecutivePollErrors.incrementAndGet();
                LOG.log(Level.WARNING, "Claim cycle failed.", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            lastCycleMillis.set(clock.millis());
            try {
                Thread.sleep(pause.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Claims and sends one batch.
     *
     * @return Number of rows claimed.
     */
    int runCycle() throws DeliveryStoreException, InterruptedException {

        Instant claimedAt = clock.instant();
        List<ClaimedDelivery> batch = queue.claim(settings.workerId, settings.batchSize, settings.lease);
        if (batch.isEmpty()) {
            return 0;
        }
        Instant sendDeadline = claimedAt.plus(settings.lease).minus(settings.readTimeout).minus(RECORD_MARGIN);
        Map<String, List<ClaimedDelivery>> byWebhook = new LinkedHashMap<>();
        for (ClaimedDelivery row : batch) {
            byWebhook.computeIfAbsent(row.getWebhookId(), id -> new ArrayList<>()).add(row);
        }
        LOG.fine(() -> "Claimed " + batch.size() + " due deliveries for " + byWebhook.size() + " webhooks.");
        List<Future<?>> groups = new ArrayList<>();
        for (List<ClaimedDelivery> rows : byWebhook.values()) {
            groups.add(senders.submit(() -> sender.send(rows, sendDeadline)));
        }
        for (Future<?> group : groups) {
            try {
                group.get();
            } catch (ExecutionException e) {
                LOG.log(Level.WARNING, "A webhook group failed unexpectedly.", e.getCause());
            }
        }
        return batch.size();
    }
}
