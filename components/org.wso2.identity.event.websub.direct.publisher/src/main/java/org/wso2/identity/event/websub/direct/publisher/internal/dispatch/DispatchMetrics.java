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

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters of the IS delivery path, named after the metrics in the design. They are kept in memory; exporting them
 * is left to the deployment's monitoring setup.
 */
public final class DispatchMetrics {

    private final AtomicLong directDelivered = new AtomicLong();
    private final AtomicLong directFailed = new AtomicLong();
    private final AtomicLong storedPending = new AtomicLong();
    private final AtomicLong storedFailed = new AtomicLong();
    private final AtomicLong storeFailures = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong circuitOpened = new AtomicLong();
    private final AtomicLong skippedByCircuit = new AtomicLong();
    private final AtomicLong skippedByCapacity = new AtomicLong();

    void directDelivered() {

        directDelivered.incrementAndGet();
    }

    void directFailed() {

        directFailed.incrementAndGet();
    }

    void stored(boolean pending) {

        (pending ? storedPending : storedFailed).incrementAndGet();
    }

    void storeFailure() {

        storeFailures.incrementAndGet();
    }

    void dropped() {

        dropped.incrementAndGet();
    }

    void circuitOpened() {

        circuitOpened.incrementAndGet();
    }

    void skippedByCircuit() {

        skippedByCircuit.incrementAndGet();
    }

    void skippedByCapacity() {

        skippedByCapacity.incrementAndGet();
    }

    /**
     * @return webhook_direct_delivery_total{result="success"}.
     */
    public long getDirectDelivered() {

        return directDelivered.get();
    }

    /**
     * @return webhook_direct_delivery_total{result="failure"}.
     */
    public long getDirectFailed() {

        return directFailed.get();
    }

    public long getStoredPending() {

        return storedPending.get();
    }

    public long getStoredFailed() {

        return storedFailed.get();
    }

    /**
     * @return webhook_store_failures_total: database writes that failed and were retried or dropped.
     */
    public long getStoreFailures() {

        return storeFailures.get();
    }

    /**
     * @return webhook_store_dropped_total: deliveries lost. Any value above zero must alert.
     */
    public long getDropped() {

        return dropped.get();
    }

    public long getCircuitOpened() {

        return circuitOpened.get();
    }

    public long getSkippedByCircuit() {

        return skippedByCircuit.get();
    }

    public long getSkippedByCapacity() {

        return skippedByCapacity.get();
    }
}
