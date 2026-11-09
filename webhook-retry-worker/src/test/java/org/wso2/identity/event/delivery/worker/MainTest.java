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
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.wso2.identity.event.delivery.core.store.PostgresTestSupport;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Starts the whole worker against PostgreSQL, as the container would, and checks its admin endpoints.
 */
public class MainTest {

    private Path keys;

    @BeforeClass
    public void setUp() throws Exception {

        PostgresTestSupport.start();
        keys = Files.createTempDirectory("keys");
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 3);
        Files.write(keys.resolve("test-key"), Base64.getEncoder().encode(key));
    }

    @AfterClass
    public void tearDown() throws IOException {

        try (Stream<Path> paths = Files.walk(keys)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    private WorkerConfig config(int port) throws IOException {

        Map<String, String> env = WorkerConfigTest.minimal();
        env.put("DB_URL", PostgresTestSupport.jdbcUrl());
        env.put("DB_USER", PostgresTestSupport.WORKER_USER);
        env.put("DB_PASSWORD", PostgresTestSupport.PASSWORD);
        env.put("ENCRYPTION_KEYS_DIR", keys.toString());
        env.put("ENCRYPTION_ACTIVE_KEY_ID", "test-key");
        env.put("HTTP_PORT", String.valueOf(port));
        env.put("ALLOW_PRIVATE_ADDRESSES", "true");
        return WorkerConfig.from(env);
    }

    private static int freePort() throws IOException {

        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static int get(int port, String path, StringBuilder body) throws IOException {

        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path)
                .openConnection();
        int status = connection.getResponseCode();
        try (InputStream in = status < 400 ? connection.getInputStream() : connection.getErrorStream()) {
            body.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        return status;
    }

    @Test
    public void testMigrateIsIdempotent() throws Exception {

        // The test database is already migrated, so nothing is applied.
        Assert.assertEquals(Main.migrate(PostgresTestSupport.adminDataSource(), config(8080)), 0);
    }

    @Test
    public void testStartServesProbesAndMetrics() throws Exception {

        int port = freePort();
        Main.Running running = Main.start(config(port));
        try {
            long deadline = System.nanoTime() + 5_000_000_000L;
            StringBuilder ready = new StringBuilder();
            int readyStatus = get(port, "/health/ready", ready);
            while (readyStatus != 200 && System.nanoTime() < deadline) {
                Thread.sleep(50);
                ready.setLength(0);
                readyStatus = get(port, "/health/ready", ready);
            }
            Assert.assertEquals(readyStatus, 200);
            Assert.assertEquals(ready.toString(), "ok");
            Assert.assertEquals(get(port, "/health/live", new StringBuilder()), 200);
            StringBuilder metrics = new StringBuilder();
            Assert.assertEquals(get(port, "/metrics", metrics), 200);
            Assert.assertTrue(metrics.toString().contains("webhook_retry_delivery_total{result=\"delivered\"}"));
            Assert.assertEquals(running.admin().getPort(), port);
            Assert.assertTrue(running.worker().isAlive());
            Assert.assertEquals(running.metrics().getPollErrors(), 0);
        } finally {
            running.stop(Duration.ofSeconds(2));
        }
    }

    @Test
    public void testAdminServerReportsUnavailable() throws Exception {

        AdminServer server = new AdminServer(0, () -> false, () -> false, () -> "");
        server.start();
        try {
            Assert.assertEquals(get(server.getPort(), "/health/live", new StringBuilder()), 503);
            Assert.assertEquals(get(server.getPort(), "/health/ready", new StringBuilder()), 503);
        } finally {
            server.stop();
        }
    }
}
