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

package org.wso2.identity.event.delivery.core.store;

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.function.UnaryOperator;

/**
 * Validation tests for {@link NewDelivery}. They need no database.
 */
public class NewDeliveryTest {

    @Test
    public void testBuilderCopiesValues() {

        byte[] payload = {1, 2, 3};
        NewDelivery delivery = PostgresTestSupport.delivery("wh-1").payload(payload).build();
        payload[0] = 9;

        Assert.assertEquals(delivery.getPayload(), new byte[]{1, 2, 3}, "The builder must copy the payload.");
        Assert.assertEquals(delivery.getWebhookId(), "wh-1");
        Assert.assertEquals(delivery.getRootTenantId(), "root-1");
        Assert.assertEquals(delivery.getStatus(), DeliveryStatus.PENDING);
        Assert.assertEquals(delivery.getLastStatusCode(), Integer.valueOf(503));
        Assert.assertEquals(delivery.getLastError(), "Service Unavailable");
        Assert.assertEquals(delivery.getRetryWindow(), Duration.ofHours(24));
        Assert.assertEquals(delivery.getFirstRetryDelay(), Duration.ZERO);
        Assert.assertEquals(delivery.getAttemptCount(), 1);
        Assert.assertTrue(delivery.getTopic().endsWith(".login"));
        Assert.assertTrue(delivery.getEventType().endsWith("loginSuccess"));
        Assert.assertTrue(delivery.getEndpointUrl().startsWith("https://"));
        Assert.assertFalse(delivery.getHeaders().isEmpty());
    }

    @DataProvider
    public Object[][] invalidBuilders() {

        char[] longId = new char[65];
        Arrays.fill(longId, 'x');
        return new Object[][]{
                {(UnaryOperator<NewDelivery.Builder>) b -> b.eventId(null)},
                {(UnaryOperator<NewDelivery.Builder>) b -> b.eventId("")},
                {(UnaryOperator<NewDelivery.Builder>) b -> b.webhookId(new String(longId))},
                {(UnaryOperator<NewDelivery.Builder>) b -> b.payload(null)},
                {(UnaryOperator<NewDelivery.Builder>) b -> b.headers(null)},
                {(UnaryOperator<NewDelivery.Builder>) b -> b.status(DeliveryStatus.IN_PROGRESS)},
                {(UnaryOperator<NewDelivery.Builder>) b -> b.status(DeliveryStatus.CANCELLED)},
                {(UnaryOperator<NewDelivery.Builder>) b -> b.attemptCount(-1)},
                {(UnaryOperator<NewDelivery.Builder>) b -> b.firstRetryDelay(null)},
                {(UnaryOperator<NewDelivery.Builder>) b -> b.retryWindow(Duration.ofSeconds(-1))},
        };
    }

    @Test(dataProvider = "invalidBuilders", expectedExceptions = IllegalArgumentException.class)
    public void testInvalidDeliveryRejected(UnaryOperator<NewDelivery.Builder> change) {

        change.apply(PostgresTestSupport.delivery("wh-1")).build();
    }
}
