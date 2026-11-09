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
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tests for {@link CircuitBreaker}.
 */
public class CircuitBreakerTest {

    private final AtomicLong clock = new AtomicLong(42);

    @Test
    public void testOpensAfterThresholdAndClosesAfterPeriod() {

        CircuitBreaker breaker = new CircuitBreaker(3, Duration.ofSeconds(60), clock::get);

        Assert.assertFalse(breaker.recordFailure("wh-1"));
        Assert.assertFalse(breaker.recordFailure("wh-1"));
        Assert.assertFalse(breaker.isOpen("wh-1"));
        Assert.assertTrue(breaker.recordFailure("wh-1"), "The third failure opens the circuit.");
        Assert.assertTrue(breaker.isOpen("wh-1"));
        Assert.assertFalse(breaker.isOpen("wh-2"), "Other webhooks are not affected.");

        clock.addAndGet(Duration.ofSeconds(61).toNanos());

        Assert.assertFalse(breaker.isOpen("wh-1"), "After the period the next event is tried.");
    }

    @Test
    public void testFailedTrialReopensAtOnce() {

        CircuitBreaker breaker = new CircuitBreaker(2, Duration.ofSeconds(60), clock::get);
        breaker.recordFailure("wh-1");
        breaker.recordFailure("wh-1");
        clock.addAndGet(Duration.ofSeconds(61).toNanos());

        Assert.assertTrue(breaker.recordFailure("wh-1"));
        Assert.assertTrue(breaker.isOpen("wh-1"));
    }

    @Test
    public void testFailureWhileOpenDoesNotCountAsNewOpening() {

        CircuitBreaker breaker = new CircuitBreaker(1, Duration.ofSeconds(60), clock::get);

        Assert.assertTrue(breaker.recordFailure("wh-1"));
        Assert.assertFalse(breaker.recordFailure("wh-1"));
    }

    @Test
    public void testSuccessResets() {

        CircuitBreaker breaker = new CircuitBreaker(2, Duration.ofSeconds(60), clock::get);
        breaker.recordFailure("wh-1");
        Assert.assertEquals(breaker.trackedWebhooks(), 1);

        breaker.recordSuccess("wh-1");

        Assert.assertEquals(breaker.trackedWebhooks(), 0);
        Assert.assertFalse(breaker.recordFailure("wh-1"), "The count starts again after a success.");
    }

    @Test
    public void testDefaultClock() {

        CircuitBreaker breaker = new CircuitBreaker(1, Duration.ofSeconds(60));
        breaker.recordFailure("wh-1");

        Assert.assertTrue(breaker.isOpen("wh-1"));
    }
}
