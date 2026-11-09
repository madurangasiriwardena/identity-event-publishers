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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Internal HTTP endpoints for Kubernetes probes and metric scraping. It serves no customer traffic and should not be
 * exposed outside the cluster.
 * <ul>
 *     <li>{@code /health/live}: 200 while the claim loop is running.</li>
 *     <li>{@code /health/ready}: 200 while claims reach the database.</li>
 *     <li>{@code /metrics}: Prometheus text format.</li>
 * </ul>
 */
public final class AdminServer {

    private final HttpServer server;

    /**
     * @param port    Port to listen on; 0 picks a free port.
     * @param live    Liveness check.
     * @param ready   Readiness check.
     * @param metrics Metrics text.
     * @throws IOException If the port cannot be bound.
     */
    public AdminServer(int port, BooleanSupplier live, BooleanSupplier ready, Supplier<String> metrics)
            throws IOException {

        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health/live", exchange -> status(exchange, live.getAsBoolean()));
        server.createContext("/health/ready", exchange -> status(exchange, ready.getAsBoolean()));
        server.createContext("/metrics", exchange -> respond(exchange, 200, "text/plain; version=0.0.4",
                metrics.get()));
    }

    public void start() {

        server.start();
    }

    public void stop() {

        server.stop(0);
    }

    /**
     * @return The bound port.
     */
    public int getPort() {

        return server.getAddress().getPort();
    }

    private static void status(HttpExchange exchange, boolean ok) throws IOException {

        respond(exchange, ok ? 200 : 503, "text/plain", ok ? "ok" : "unavailable");
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        } finally {
            exchange.close();
        }
    }
}
