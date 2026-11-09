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

import org.wso2.identity.event.delivery.core.store.QueueStats;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Worker metrics, exposed in the Prometheus text format on {@code /metrics}. Names follow design section 13. An
 * OpenTelemetry Collector or Prometheus scrapes them on both clouds.
 */
public final class WorkerMetrics {

    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong retried = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong expired = new AtomicLong();
    private final AtomicLong released = new AtomicLong();
    private final AtomicLong leaseLost = new AtomicLong();
    private final AtomicLong leasesRecovered = new AtomicLong();
    private final AtomicLong purged = new AtomicLong();
    private final AtomicLong pollErrors = new AtomicLong();
    private final AtomicReference<QueueStats> queueStats = new AtomicReference<>();

    void delivered() {

        delivered.incrementAndGet();
    }

    void retried() {

        retried.incrementAndGet();
    }

    void failed() {

        failed.incrementAndGet();
    }

    void expired(long count) {

        expired.addAndGet(count);
    }

    void released() {

        released.incrementAndGet();
    }

    void leaseLost() {

        leaseLost.incrementAndGet();
    }

    void leasesRecovered(long count) {

        leasesRecovered.addAndGet(count);
    }

    void purged(long count) {

        purged.addAndGet(count);
    }

    void pollError() {

        pollErrors.incrementAndGet();
    }

    void queueStats(QueueStats stats) {

        queueStats.set(stats);
    }

    public long getDelivered() {

        return delivered.get();
    }

    public long getRetried() {

        return retried.get();
    }

    public long getFailed() {

        return failed.get();
    }

    public long getExpired() {

        return expired.get();
    }

    public long getReleased() {

        return released.get();
    }

    public long getLeaseLost() {

        return leaseLost.get();
    }

    public long getLeasesRecovered() {

        return leasesRecovered.get();
    }

    public long getPollErrors() {

        return pollErrors.get();
    }

    /**
     * @return All metrics in the Prometheus text exposition format.
     */
    public String toPrometheusText() {

        StringBuilder out = new StringBuilder();
        out.append("# HELP webhook_retry_delivery_total Retry attempts by result.\n")
                .append("# TYPE webhook_retry_delivery_total counter\n");
        counter(out, "webhook_retry_delivery_total{result=\"delivered\"}", delivered);
        counter(out, "webhook_retry_delivery_total{result=\"retry\"}", retried);
        counter(out, "webhook_retry_delivery_total{result=\"failed\"}", failed);
        out.append("# HELP webhook_expired_total Deliveries not delivered within the retry window.\n")
                .append("# TYPE webhook_expired_total counter\n");
        counter(out, "webhook_expired_total", expired);
        out.append("# HELP webhook_released_total Claimed rows returned unsent, for example after an endpoint "
                + "failure in the same batch.\n# TYPE webhook_released_total counter\n");
        counter(out, "webhook_released_total", released);
        out.append("# HELP webhook_lease_lost_total Results not recorded because the lease had moved on.\n")
                .append("# TYPE webhook_lease_lost_total counter\n");
        counter(out, "webhook_lease_lost_total", leaseLost);
        out.append("# HELP webhook_lease_recovered_total Leases released after a replica stopped or hung.\n")
                .append("# TYPE webhook_lease_recovered_total counter\n");
        counter(out, "webhook_lease_recovered_total", leasesRecovered);
        out.append("# HELP webhook_purged_total Final rows deleted after the retention period.\n")
                .append("# TYPE webhook_purged_total counter\n");
        counter(out, "webhook_purged_total", purged);
        out.append("# HELP webhook_poll_errors_total Failed claim cycles.\n")
                .append("# TYPE webhook_poll_errors_total counter\n");
        counter(out, "webhook_poll_errors_total", pollErrors);
        QueueStats stats = queueStats.get();
        if (stats != null) {
            out.append("# HELP webhook_pending Rows waiting for a later attempt.\n# TYPE webhook_pending gauge\n")
                    .append("webhook_pending ").append(stats.getPending()).append('\n')
                    .append("# HELP webhook_pending_due Pending rows whose next attempt time has passed.\n")
                    .append("# TYPE webhook_pending_due gauge\n")
                    .append("webhook_pending_due ").append(stats.getDue()).append('\n')
                    .append("# HELP webhook_in_progress Rows claimed by a replica.\n")
                    .append("# TYPE webhook_in_progress gauge\n")
                    .append("webhook_in_progress ").append(stats.getInProgress()).append('\n')
                    .append("# HELP webhook_oldest_pending_age_seconds Age of the oldest pending row.\n")
                    .append("# TYPE webhook_oldest_pending_age_seconds gauge\n")
                    .append("webhook_oldest_pending_age_seconds ")
                    .append(stats.getOldestPendingAge().getSeconds()).append('\n');
        }
        return out.toString();
    }

    private static void counter(StringBuilder out, String name, AtomicLong value) {

        out.append(name).append(' ').append(value.get()).append('\n');
    }
}
