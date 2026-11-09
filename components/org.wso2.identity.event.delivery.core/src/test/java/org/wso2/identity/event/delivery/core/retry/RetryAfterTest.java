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
import java.time.Instant;
import java.util.Optional;

/**
 * Tests for {@link RetryAfter}.
 */
public class RetryAfterTest {

    private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");

    @Test
    public void testDeltaSeconds() {

        Assert.assertEquals(RetryAfter.parse("120", NOW), Optional.of(Duration.ofSeconds(120)));
        Assert.assertEquals(RetryAfter.parse(" 0 ", NOW), Optional.of(Duration.ZERO));
    }

    @Test
    public void testHttpDate() {

        Assert.assertEquals(RetryAfter.parse("Fri, 06 Nov 2026 10:05:00 GMT", NOW),
                Optional.of(Duration.ofMinutes(5)));
    }

    @Test
    public void testHttpDateInThePastIsZero() {

        Assert.assertEquals(RetryAfter.parse("Fri, 06 Nov 2026 09:00:00 GMT", NOW), Optional.of(Duration.ZERO));
    }

    @DataProvider
    public Object[][] unusableValues() {

        return new Object[][]{
                {null},
                {""},
                {"  "},
                {"-5"},
                {"soon"},
                {"99999999999999999999999"},
        };
    }

    @Test(dataProvider = "unusableValues")
    public void testUnusableValueIsEmpty(String value) {

        Assert.assertFalse(RetryAfter.parse(value, NOW).isPresent());
    }
}
