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

import org.wso2.identity.event.delivery.core.store.DeliveryQueue;
import org.wso2.identity.event.delivery.core.store.DeliveryStoreException;
import org.wso2.identity.event.delivery.core.store.QueueStats;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Background jobs of every replica (design section 5.2). They are safe to run on all replicas at once, because each
 * is a single conditional statement or uses SKIP LOCKED.
 * <ul>
 *     <li>Lease reaper: rows whose lease expired go back to PENDING.</li>
 *     <li>Expiry sweep: PENDING rows past their retry window become EXPIRED.</li>
 *     <li>Purge: final rows and idle endpoint state older than the retention period are deleted.</li>
 *     <li>Stats: queue size and age for the metrics endpoint.</li>
 * </ul>
 */
public final class Housekeeper {

    private static final Logger LOG = Logger.getLogger(Housekeeper.class.getName());
    // Bounds one sweep or purge run, so a huge backlog is worked through over several runs.
    private static final int MAX_BATCHES_PER_RUN = 100;

    /**
     * Job settings.
     */
    public static final class Settings {

        private final Duration reaperInterval;
        private final Duration expiryInterval;
        private final Duration purgeInterval;
        private final Duration statsInterval;
        private final Duration retention;
        private final int batchSize;

        public Settings(Duration reaperInterval, Duration expiryInterval, Duration purgeInterval,
                        Duration statsInterval, Duration retention, int batchSize) {

            this.reaperInterval = reaperInterval;
            this.expiryInterval = expiryInterval;
            this.purgeInterval = purgeInterval;
            this.statsInterval = statsInterval;
            this.retention = retention;
            this.batchSize = batchSize;
        }
    }

    private final DeliveryQueue queue;
    private final WorkerMetrics metrics;
    private final Settings settings;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "webhook-housekeeper");
        thread.setDaemon(true);
        return thread;
    });

    public Housekeeper(DeliveryQueue queue, WorkerMetrics metrics, Settings settings) {

        this.queue = queue;
        this.metrics = metrics;
        this.settings = settings;
    }

    /**
     * Schedules all jobs.
     */
    public void start() {

        schedule(this::reapLeases, settings.reaperInterval);
        schedule(this::expireOverdue, settings.expiryInterval);
        schedule(this::purge, settings.purgeInterval);
        schedule(this::refreshStats, settings.statsInterval);
    }

    /**
     * Stops the jobs. A job that is running finishes its current statement.
     */
    public void stop() {

        scheduler.shutdownNow();
    }

    void reapLeases() {

        run("Lease reaper", () -> {
            int released = queue.releaseExpiredLeases();
            if (released > 0) {
                metrics.leasesRecovered(released);
                LOG.warning("Released " + released + " deliveries whose lease expired. A replica stopped or hung.");
            }
        });
    }

    void expireOverdue() {

        run("Expiry sweep", () -> {
            int total = 0;
            for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
                int expired = queue.expireOverdue(settings.batchSize);
                metrics.expired(expired);
                total += expired;
                if (expired < settings.batchSize) {
                    break;
                }
            }
            if (total > 0) {
                int count = total;
                LOG.fine(() -> "Expiry sweep: " + count + " deliveries passed their retry window.");
            }
        });
    }

    void purge() {

        run("Purge", () -> {
            int total = 0;
            for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
                int purged = queue.purgeTerminal(settings.retention, settings.batchSize);
                metrics.purged(purged);
                total += purged;
                if (purged < settings.batchSize) {
                    break;
                }
            }
            int states = queue.purgeEndpointStates(settings.retention);
            if (total > 0 || states > 0) {
                int rows = total;
                LOG.fine(() -> "Purge: deleted " + rows + " final deliveries and " + states
                        + " idle endpoint states.");
            }
        });
    }

    void refreshStats() {

        run("Queue statistics", () -> {
            QueueStats stats = queue.stats();
            metrics.queueStats(stats);
            if (stats.getPending() + stats.getInProgress() > 0) {
                LOG.fine(() -> "Queue: " + stats.getPending() + " pending (" + stats.getDue() + " due), "
                        + stats.getInProgress() + " in progress, oldest pending "
                        + stats.getOldestPendingAge().getSeconds() + " s.");
            }
        });
    }

    private void schedule(Runnable job, Duration interval) {

        scheduler.scheduleWithFixedDelay(job, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static void run(String name, Job job) {

        try {
            job.run();
        } catch (DeliveryStoreException | RuntimeException e) {
            // A failed run is retried at the next interval; an exception must not cancel the schedule.
            LOG.log(Level.WARNING, name + " failed.", e);
        }
    }

    /**
     * A job that can fail with a database error.
     */
    private interface Job {

        void run() throws DeliveryStoreException;
    }
}
