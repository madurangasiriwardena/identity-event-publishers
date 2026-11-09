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

package org.wso2.identity.event.delivery.core.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * A local HTTP server that plays a webhook endpoint in tests. Each test sets how it answers.
 */
public final class TestEndpoint implements AutoCloseable {

    /**
     * How the endpoint answers one request.
     */
    public static final class Reply {

        private final int status;
        private final String body;
        private final long delayMillis;
        private final String retryAfter;
        private final String location;

        public Reply(int status, String body, long delayMillis, String retryAfter, String location) {

            this.status = status;
            this.body = body;
            this.delayMillis = delayMillis;
            this.retryAfter = retryAfter;
            this.location = location;
        }

        public static Reply status(int status) {

            return new Reply(status, "", 0, null, null);
        }
    }

    /**
     * What the endpoint received.
     */
    public static final class Received {

        public final String method;
        public final String query;
        public final Map<String, List<String>> headers;
        public final byte[] body;

        Received(String method, String query, Map<String, List<String>> headers, byte[] body) {

            this.method = method;
            this.query = query;
            this.headers = headers;
            this.body = body;
        }

        public String header(String name) {

            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue().get(0);
                }
            }
            return null;
        }

        public String queryParameter(String name) {

            if (query == null) {
                return null;
            }
            for (String pair : query.split("&")) {
                int equals = pair.indexOf('=');
                if (equals > 0 && pair.substring(0, equals).equals(name)) {
                    return URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
                }
            }
            return null;
        }
    }

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile Function<Received, Reply> responder = request -> Reply.status(200);

    public TestEndpoint() throws IOException {

        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    public void respond(Function<Received, Reply> responder) {

        this.responder = responder;
    }

    public String url(String path) {

        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    public List<Received> received() {

        return received;
    }

    private void handle(HttpExchange exchange) throws IOException {

        byte[] body;
        try (InputStream in = exchange.getRequestBody()) {
            body = in.readAllBytes();
        }
        Received request = new Received(exchange.getRequestMethod(), exchange.getRequestURI().getRawQuery(),
                exchange.getRequestHeaders(), body);
        received.add(request);
        Reply reply = responder.apply(request);
        if (reply.delayMillis > 0) {
            try {
                Thread.sleep(reply.delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (reply.retryAfter != null) {
            exchange.getResponseHeaders().add("Retry-After", reply.retryAfter);
        }
        if (reply.location != null) {
            exchange.getResponseHeaders().add("Location", reply.location);
        }
        byte[] out = reply.body.getBytes(StandardCharsets.UTF_8);
        try {
            exchange.sendResponseHeaders(reply.status, out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            }
        } catch (IOException e) {
            // The client gave up, for example after its read timeout.
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {

        server.stop(0);
    }
}
