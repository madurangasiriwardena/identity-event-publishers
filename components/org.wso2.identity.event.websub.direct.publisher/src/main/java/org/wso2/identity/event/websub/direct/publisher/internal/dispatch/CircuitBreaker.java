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

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/**
 * Per-node, per-webhook circuit breaker.
 * <p>
 * After {@code failureThreshold} failures in a row, the circuit for that webhook opens for {@code openDuration}.
 * While it is open, IS skips the direct attempt and stores the event at once, so a dead endpoint does not hold
 * connections. When the period ends, the next event is tried again. If that try fails, the circuit opens again at
 * once, because the failure count is still at or above the threshold. A success removes the webhook's state.
 */
public final class CircuitBreaker {

    private final int failureThreshold;
    private final long openNanos;
    private final LongSupplier nanoClock;
    private final ConcurrentMap<String, State> states = new ConcurrentHashMap<>();

    /**
     * @param failureThreshold Consecutive failures that open the circuit.
     * @param openDuration     How long the circuit stays open.
     */
    public CircuitBreaker(int failureThreshold, Duration openDuration) {

        this(failureThreshold, openDuration, System::nanoTime);
    }

    /**
     * @param failureThreshold Consecutive failures that open the circuit.
     * @param openDuration     How long the circuit stays open.
     * @param nanoClock        Monotonic clock; tests pass a controlled one.
     */
    public CircuitBreaker(int failureThreshold, Duration openDuration, LongSupplier nanoClock) {

        this.failureThreshold = failureThreshold;
        this.openNanos = openDuration.toNanos();
        this.nanoClock = nanoClock;
    }

    /**
     * @param webhookId Webhook ID.
     * @return True if direct attempts to this webhook are paused.
     */
    public boolean isOpen(String webhookId) {

        State state = states.get(webhookId);
        return state != null && state.isOpen(nanoClock.getAsLong());
    }

    /**
     * @param webhookId Webhook whose endpoint accepted an event.
     */
    public void recordSuccess(String webhookId) {

        states.remove(webhookId);
    }

    /**
     * @param webhookId Webhook whose endpoint failed.
     * @return True if this failure opened the circuit.
     */
    public boolean recordFailure(String webhookId) {

        State state = states.computeIfAbsent(webhookId, id -> new State());
        return state.fail(nanoClock.getAsLong(), failureThreshold, openNanos);
    }

    /**
     * @return Number of webhooks with recent failures on this node.
     */
    public int trackedWebhooks() {

        return states.size();
    }

    private static final class State {

        private int failures;
        private long openUntil;
        private boolean open;

        synchronized boolean isOpen(long now) {

            return open && now - openUntil < 0;
        }

        synchronized boolean fail(long now, int threshold, long openNanos) {

            failures++;
            if (failures >= threshold) {
                boolean wasOpen = open && now - openUntil < 0;
                open = true;
                openUntil = now + openNanos;
                return !wasOpen;
            }
            return false;
        }
    }
}
