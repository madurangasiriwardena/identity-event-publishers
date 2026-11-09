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

package org.wso2.identity.event.websub.direct.publisher.config;

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.wso2.identity.event.delivery.core.http.ResponseClassifier;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * Tests for {@link WebSubDirectConfiguration}.
 */
public class WebSubDirectConfigurationTest {

    private static Map<String, String> enabled() {

        Map<String, String> properties = new HashMap<>();
        properties.put("enabled", "true");
        properties.put("hubUrl", "https://hub.example.com");
        properties.put("deliveryDataSource", "jdbc/WebhookDeliveryDB");
        return properties;
    }

    @Test
    public void testDefaults() throws Exception {

        WebSubDirectConfiguration config = new WebSubDirectConfiguration(enabled());

        Assert.assertTrue(config.isEnabled());
        Assert.assertEquals(config.getHubUrl(), "https://hub.example.com");
        Assert.assertEquals(config.getDataSourceName(), "jdbc/WebhookDeliveryDB");
        Assert.assertEquals(config.getConnectTimeoutMillis(), 2000);
        Assert.assertEquals(config.getReadTimeoutMillis(), 5000);
        Assert.assertEquals(config.getConnectionRequestTimeoutMillis(), 500);
        Assert.assertEquals(config.getMaxConnections(), 200);
        Assert.assertEquals(config.getMaxConnectionsPerRoute(), 20);
        Assert.assertEquals(config.getIoThreadCount(), 4);
        Assert.assertEquals(config.getMaxInFlight(), 500);
        Assert.assertEquals(config.getCallbackThreads(), 8);
        Assert.assertEquals(config.getCallbackQueueCapacity(), 2000);
        Assert.assertEquals(config.getStoreThreads(), 4);
        Assert.assertEquals(config.getStoreBufferMaxEntries(), 10000);
        Assert.assertEquals(config.getStoreBufferMaxAge(), Duration.ofMinutes(3));
        Assert.assertEquals(config.getFirstRetryDelay(), Duration.ofSeconds(30));
        Assert.assertEquals(config.getRetryWindow(), Duration.ofHours(24));
        Assert.assertEquals(config.getMaxPendingPerWebhook(), 100000);
        Assert.assertEquals(config.getCircuitFailureThreshold(), 5);
        Assert.assertEquals(config.getCircuitOpenDuration(), Duration.ofMinutes(1));
        Assert.assertEquals(config.getVerificationTimeoutMillis(), 10000);
        Assert.assertEquals(config.getLeaseSeconds(), 864000);
        Assert.assertEquals(config.getShutdownGrace(), Duration.ofSeconds(20));
        Assert.assertFalse(config.isAllowHttpEndpoints());
        Assert.assertFalse(config.isAllowPrivateAddresses());
        Assert.assertEquals(config.getRetryableStatusCodes(), ResponseClassifier.DEFAULT_RETRYABLE_STATUS_CODES);
        Assert.assertEquals(config.getPermanentStatusCodes(), ResponseClassifier.DEFAULT_PERMANENT_STATUS_CODES);
    }

    @Test
    public void testOverrides() throws Exception {

        Map<String, String> properties = enabled();
        properties.put("readTimeout", " 8000 ");
        properties.put("allowHttpEndpoints", "true");
        properties.put("allowPrivateAddresses", "true");
        properties.put("retryableStatusCodes", "500, 503");
        properties.put("permanentStatusCodes", "400");

        WebSubDirectConfiguration config = new WebSubDirectConfiguration(properties);

        Assert.assertEquals(config.getReadTimeoutMillis(), 8000);
        Assert.assertTrue(config.isAllowHttpEndpoints());
        Assert.assertTrue(config.isAllowPrivateAddresses());
        Assert.assertEquals(config.getRetryableStatusCodes(), new HashSet<>(Arrays.asList(500, 503)));
        Assert.assertEquals(config.getPermanentStatusCodes(), new HashSet<>(Arrays.asList(400)));
    }

    @Test
    public void testDisabledAdapterNeedsNoSettings() throws Exception {

        WebSubDirectConfiguration config = new WebSubDirectConfiguration(new HashMap<String, String>());

        Assert.assertFalse(config.isEnabled());
        Assert.assertNull(config.getHubUrl());
    }

    @DataProvider
    public Object[][] invalidSettings() {

        return new Object[][]{
                {"hubUrl", null},
                {"deliveryDataSource", " "},
                {"readTimeout", "0"},
                {"readTimeout", "abc"},
                {"retryableStatusCodes", "500,x"},
                {"firstRetryDelaySeconds", "90000"},
        };
    }

    @Test(dataProvider = "invalidSettings", expectedExceptions = WebSubDirectConfigurationException.class)
    public void testInvalidSettings(String key, String value) throws Exception {

        Map<String, String> properties = enabled();
        properties.put(key, value);

        new WebSubDirectConfiguration(properties);
    }
}
