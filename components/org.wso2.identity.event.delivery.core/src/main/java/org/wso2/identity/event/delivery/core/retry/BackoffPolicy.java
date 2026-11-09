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

package org.wso2.identity.event.delivery.core.retry;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.function.DoubleSupplier;

/**
 * Exponential backoff with jitter.
 * <p>
 * The delay after the n-th consecutive failure is {@code min(max, initial * factor^(n-1))}, then varied randomly
 * by up to {@code jitter} in either direction, so many endpoints that fail together do not retry together. Because
 * of the jitter, a delay can exceed {@code max} by up to {@code max * jitter}.
 */
public final class BackoffPolicy {

    private final Duration initialInterval;
    private final double factor;
    private final Duration maxInterval;
    private final double jitter;
    private final DoubleSupplier random;

    /**
     * @param initialInterval Delay after the first failure.
     * @param factor          Multiplier applied after each further failure. Must be at least 1.
     * @param maxInterval     Upper bound before jitter.
     * @param jitter          Fraction of random variation, from 0 to 1.
     */
    public BackoffPolicy(Duration initialInterval, double factor, Duration maxInterval, double jitter) {

        this(initialInterval, factor, maxInterval, jitter, new SecureRandom()::nextDouble);
    }

    /**
     * @param initialInterval Delay after the first failure.
     * @param factor          Multiplier applied after each further failure. Must be at least 1.
     * @param maxInterval     Upper bound before jitter.
     * @param jitter          Fraction of random variation, from 0 to 1.
     * @param random          Source of uniform values in [0, 1). Tests pass a fixed value.
     */
    public BackoffPolicy(Duration initialInterval, double factor, Duration maxInterval, double jitter,
                         DoubleSupplier random) {

        if (initialInterval == null || initialInterval.isNegative() || initialInterval.isZero()) {
            throw new IllegalArgumentException("The initial interval must be positive.");
        }
        if (maxInterval == null || maxInterval.compareTo(initialInterval) < 0) {
            throw new IllegalArgumentException("The max interval must not be shorter than the initial interval.");
        }
        if (Double.isNaN(factor) || factor < 1.0) {
            throw new IllegalArgumentException("The backoff factor must be at least 1.");
        }
        if (Double.isNaN(jitter) || jitter < 0.0 || jitter > 1.0) {
            throw new IllegalArgumentException("The jitter must be between 0 and 1.");
        }
        this.initialInterval = initialInterval;
        this.factor = factor;
        this.maxInterval = maxInterval;
        this.jitter = jitter;
        this.random = random;
    }

    /**
     * @param consecutiveFailures Number of failures in a row, starting at 1.
     * @return How long to wait before the next attempt.
     */
    public Duration delayAfter(int consecutiveFailures) {

        if (consecutiveFailures < 1) {
            throw new IllegalArgumentException("The failure count starts at 1.");
        }
        double base = initialInterval.toMillis() * Math.pow(factor, consecutiveFailures - 1.0);
        double capped = Math.min(base, maxInterval.toMillis());
        double varied = capped * (1.0 + jitter * (2.0 * random.getAsDouble() - 1.0));
        return Duration.ofMillis(Math.max(0L, Math.round(varied)));
    }

    /**
     * @return Delay after the first failure.
     */
    public Duration getInitialInterval() {

        return initialInterval;
    }

    /**
     * @return Upper bound before jitter.
     */
    public Duration getMaxInterval() {

        return maxInterval;
    }
}
