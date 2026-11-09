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

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.time.Duration;

/**
 * Tests for {@link BackoffPolicy}.
 */
public class BackoffPolicyTest {

    private static final Duration INITIAL = Duration.ofSeconds(30);
    private static final Duration MAX = Duration.ofHours(1);

    @Test
    public void testExponentialGrowthWithoutJitter() {

        // A random value of 0.5 means no variation.
        BackoffPolicy policy = new BackoffPolicy(INITIAL, 2.0, MAX, 0.1, () -> 0.5);

        Assert.assertEquals(policy.delayAfter(1), Duration.ofSeconds(30));
        Assert.assertEquals(policy.delayAfter(2), Duration.ofSeconds(60));
        Assert.assertEquals(policy.delayAfter(3), Duration.ofSeconds(120));
        Assert.assertEquals(policy.delayAfter(7), Duration.ofSeconds(1920));
        Assert.assertEquals(policy.delayAfter(8), MAX);
        Assert.assertEquals(policy.delayAfter(1000), MAX);
    }

    @Test
    public void testJitterBounds() {

        BackoffPolicy low = new BackoffPolicy(INITIAL, 2.0, MAX, 0.1, () -> 0.0);
        BackoffPolicy high = new BackoffPolicy(INITIAL, 2.0, MAX, 0.1, () -> 0.999999);

        Assert.assertEquals(low.delayAfter(1), Duration.ofSeconds(27));
        Assert.assertTrue(high.delayAfter(1).compareTo(Duration.ofSeconds(33)) <= 0);
        Assert.assertTrue(high.delayAfter(1).compareTo(Duration.ofMillis(32999)) >= 0);
    }

    @Test
    public void testDefaultRandomStaysWithinBounds() {

        BackoffPolicy policy = new BackoffPolicy(INITIAL, 2.0, MAX, 0.1);
        for (int i = 0; i < 100; i++) {
            long millis = policy.delayAfter(2).toMillis();
            Assert.assertTrue(millis >= 54000 && millis <= 66000, "Out of bounds: " + millis);
        }
        Assert.assertEquals(policy.getInitialInterval(), INITIAL);
        Assert.assertEquals(policy.getMaxInterval(), MAX);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testFailureCountStartsAtOne() {

        new BackoffPolicy(INITIAL, 2.0, MAX, 0.1).delayAfter(0);
    }

    @DataProvider
    public Object[][] invalidSettings() {

        return new Object[][]{
                {Duration.ZERO, 2.0, MAX, 0.1},
                {Duration.ofSeconds(-1), 2.0, MAX, 0.1},
                {null, 2.0, MAX, 0.1},
                {INITIAL, 0.5, MAX, 0.1},
                {INITIAL, Double.NaN, MAX, 0.1},
                {INITIAL, 2.0, Duration.ofSeconds(10), 0.1},
                {INITIAL, 2.0, null, 0.1},
                {INITIAL, 2.0, MAX, -0.1},
                {INITIAL, 2.0, MAX, 1.5},
        };
    }

    @Test(dataProvider = "invalidSettings", expectedExceptions = IllegalArgumentException.class)
    public void testRejectsInvalidSettings(Duration initial, double factor, Duration max, double jitter) {

        new BackoffPolicy(initial, factor, max, jitter);
    }
}
