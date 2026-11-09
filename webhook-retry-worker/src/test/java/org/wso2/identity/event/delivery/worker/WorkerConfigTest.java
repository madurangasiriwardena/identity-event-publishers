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

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.wso2.identity.event.delivery.core.http.ResponseClassifier;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * Tests for {@link WorkerConfig}.
 */
public class WorkerConfigTest {

    static Map<String, String> minimal() {

        Map<String, String> env = new HashMap<>();
        env.put("DB_URL", "jdbc:postgresql://db:5432/webhooks");
        env.put("DB_USER", "worker");
        env.put("DB_PASSWORD", "pw");
        return env;
    }

    @Test
    public void testDefaults() throws Exception {

        Map<String, String> env = minimal();
        env.put("HOSTNAME", "webhook-retry-worker-5d9c");

        WorkerConfig config = WorkerConfig.from(env);

        Assert.assertEquals(config.getDbUrl(), "jdbc:postgresql://db:5432/webhooks");
        Assert.assertEquals(config.getDbUser(), "worker");
        Assert.assertEquals(config.getDbPassword(), "pw");
        Assert.assertEquals(config.getWorkerId(), "webhook-retry-worker-5d9c");
        Assert.assertEquals(config.getBatchSize(), 100);
        Assert.assertEquals(config.getConcurrency(), 32);
        Assert.assertEquals(config.getDbPoolSize(), 36);
        Assert.assertEquals(config.getLease(), Duration.ofMinutes(2));
        Assert.assertEquals(config.getBusyPollInterval(), Duration.ofSeconds(1));
        Assert.assertEquals(config.getIdlePollInterval(), Duration.ofSeconds(5));
        Assert.assertEquals(config.getConnectTimeoutMillis(), 2000);
        Assert.assertEquals(config.getReadTimeoutMillis(), 10000);
        Assert.assertEquals(config.getConnectionRequestTimeoutMillis(), 1000);
        Assert.assertEquals(config.getMaxConnections(), 200);
        Assert.assertEquals(config.getMaxConnectionsPerRoute(), 10);
        Assert.assertEquals(config.getIoThreadCount(), 4);
        Assert.assertEquals(config.getBackoffInitial(), Duration.ofSeconds(30));
        Assert.assertEquals(config.getBackoffFactor(), 2.0);
        Assert.assertEquals(config.getBackoffMax(), Duration.ofHours(1));
        Assert.assertEquals(config.getBackoffJitter(), 0.1);
        Assert.assertEquals(config.getEndpointRejectMinInterval(), Duration.ofMinutes(5));
        Assert.assertEquals(config.getTerminalRetention(), Duration.ofDays(7));
        Assert.assertEquals(config.getReaperInterval(), Duration.ofSeconds(30));
        Assert.assertEquals(config.getExpiryInterval(), Duration.ofMinutes(1));
        Assert.assertEquals(config.getPurgeInterval(), Duration.ofMinutes(10));
        Assert.assertEquals(config.getStatsInterval(), Duration.ofSeconds(15));
        Assert.assertEquals(config.getPurgeBatchSize(), 1000);
        Assert.assertEquals(config.getHttpPort(), 8080);
        Assert.assertFalse(config.isAllowHttpEndpoints());
        Assert.assertFalse(config.isAllowPrivateAddresses());
        Assert.assertEquals(config.getRetryableStatusCodes(), ResponseClassifier.DEFAULT_RETRYABLE_STATUS_CODES);
        Assert.assertEquals(config.getPermanentStatusCodes(), ResponseClassifier.DEFAULT_PERMANENT_STATUS_CODES);
        Assert.assertEquals(config.getShutdownGrace(), Duration.ofSeconds(25));
        Assert.assertEquals(config.getIsRole(), "webhook_is");
        Assert.assertEquals(config.getWorkerRole(), "webhook_worker");
    }

    @Test
    public void testPasswordFromFileAndOverrides() throws Exception {

        Path file = Files.createTempFile("db-password", ".txt");
        try {
            Files.write(file, "from-file\n".getBytes(StandardCharsets.UTF_8));
            Map<String, String> env = minimal();
            env.remove("DB_PASSWORD");
            env.put("DB_PASSWORD_FILE", file.toString());
            env.put("WORKER_ID", "w1");
            env.put("BACKOFF_FACTOR", "1.5");
            env.put("RETRYABLE_STATUS_CODES", "500, 503");
            env.put("PERMANENT_STATUS_CODES", "400");
            env.put("ALLOW_PRIVATE_ADDRESSES", "true");

            WorkerConfig config = WorkerConfig.from(env);

            Assert.assertEquals(config.getDbPassword(), "from-file");
            Assert.assertEquals(config.getWorkerId(), "w1");
            Assert.assertEquals(config.getBackoffFactor(), 1.5);
            Assert.assertEquals(config.getRetryableStatusCodes(), new HashSet<>(Arrays.asList(500, 503)));
            Assert.assertEquals(config.getPermanentStatusCodes(), new HashSet<>(Arrays.asList(400)));
            Assert.assertTrue(config.isAllowPrivateAddresses());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void testRandomWorkerIdWithoutHostname() throws Exception {

        Assert.assertTrue(WorkerConfig.from(minimal()).getWorkerId().startsWith("worker-"));
    }

    @DataProvider
    public Object[][] invalid() {

        return new Object[][]{
                {"DB_URL", null},
                {"DB_USER", ""},
                {"DB_PASSWORD", null},
                {"BATCH_SIZE", "0"},
                {"BATCH_SIZE", "many"},
                {"BACKOFF_FACTOR", "fast"},
                {"RETRYABLE_STATUS_CODES", "500,x"},
                {"LEASE_SECONDS", "15"},
        };
    }

    @Test(dataProvider = "invalid", expectedExceptions = IllegalArgumentException.class)
    public void testInvalidSettings(String name, String value) throws Exception {

        Map<String, String> env = minimal();
        env.put(name, value);

        WorkerConfig.from(env);
    }
}
