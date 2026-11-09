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

package org.wso2.identity.event.delivery.core.http;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.DnsResolver;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.nio.client.methods.HttpAsyncMethods;
import org.apache.http.nio.conn.NoopIOSessionStrategy;
import org.apache.http.nio.conn.SchemeIOSessionStrategy;
import org.apache.http.nio.conn.ssl.SSLIOSessionStrategy;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Non-blocking HTTP client for webhook deliveries and verification requests.
 * <p>
 * Every connection resolves its host through the given DNS resolver, which rejects blocked addresses. Redirects are
 * never followed, cookies are not kept, and response bodies are read with a size limit.
 */
public class DeliveryHttpClient implements Closeable {

    /**
     * Client settings.
     */
    public static final class Settings {

        private final int connectTimeoutMillis;
        private final int readTimeoutMillis;
        private final int connectionRequestTimeoutMillis;
        private final int maxConnections;
        private final int maxConnectionsPerRoute;
        private final int ioThreadCount;

        public Settings(int connectTimeoutMillis, int readTimeoutMillis, int connectionRequestTimeoutMillis,
                        int maxConnections, int maxConnectionsPerRoute, int ioThreadCount) {

            this.connectTimeoutMillis = connectTimeoutMillis;
            this.readTimeoutMillis = readTimeoutMillis;
            this.connectionRequestTimeoutMillis = connectionRequestTimeoutMillis;
            this.maxConnections = maxConnections;
            this.maxConnectionsPerRoute = maxConnectionsPerRoute;
            this.ioThreadCount = ioThreadCount;
        }
    }

    private final CloseableHttpAsyncClient client;

    /**
     * Creates and starts the client.
     *
     * @param settings    Timeouts and pool sizes.
     * @param dnsResolver Resolver that rejects blocked addresses.
     * @throws IOException If the I/O reactor cannot be created.
     */
    public DeliveryHttpClient(Settings settings, DnsResolver dnsResolver) throws IOException {

        IOReactorConfig reactorConfig = IOReactorConfig.custom()
                .setConnectTimeout(settings.connectTimeoutMillis)
                .setSoTimeout(settings.readTimeoutMillis)
                .setIoThreadCount(settings.ioThreadCount)
                .build();
        Registry<SchemeIOSessionStrategy> schemes = RegistryBuilder.<SchemeIOSessionStrategy>create()
                .register("http", NoopIOSessionStrategy.INSTANCE)
                .register("https", SSLIOSessionStrategy.getSystemDefaultStrategy())
                .build();
        PoolingNHttpClientConnectionManager connectionManager = new PoolingNHttpClientConnectionManager(
                new DefaultConnectingIOReactor(reactorConfig), null, schemes, dnsResolver);
        connectionManager.setMaxTotal(settings.maxConnections);
        connectionManager.setDefaultMaxPerRoute(settings.maxConnectionsPerRoute);
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(settings.connectTimeoutMillis)
                .setSocketTimeout(settings.readTimeoutMillis)
                .setConnectionRequestTimeout(settings.connectionRequestTimeoutMillis)
                .setRedirectsEnabled(false)
                .setRelativeRedirectsAllowed(false)
                .build();
        this.client = HttpAsyncClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .disableCookieManagement()
                .disableAuthCaching()
                .build();
        this.client.start();
    }

    /**
     * Sends a webhook event.
     *
     * @param endpoint Validated endpoint URL.
     * @param body     Exact body bytes that were signed.
     * @param headers  Headers to send.
     * @return Completes with the response, or exceptionally with the transport error.
     */
    public CompletableFuture<HttpResult> post(URI endpoint, byte[] body, Map<String, String> headers) {

        HttpPost post = new HttpPost(endpoint);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            post.setHeader(header.getKey(), header.getValue());
        }
        post.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));
        return execute(post, 0);
    }

    /**
     * Sends a GET request, used for subscription verification.
     *
     * @param uri          Full URL, including query parameters.
     * @param maxBodyBytes Number of response body bytes to keep.
     * @return Completes with the response, or exceptionally with the transport error.
     */
    public CompletableFuture<HttpResult> get(URI uri, int maxBodyBytes) {

        return execute(new HttpGet(uri), maxBodyBytes);
    }

    private CompletableFuture<HttpResult> execute(HttpUriRequest request, int maxBodyBytes) {

        CompletableFuture<HttpResult> future = new CompletableFuture<>();
        client.execute(HttpAsyncMethods.create(request), new LimitedResponseConsumer(maxBodyBytes),
                new FutureCallback<HttpResult>() {
                    @Override
                    public void completed(HttpResult result) {

                        future.complete(result);
                    }

                    @Override
                    public void failed(Exception error) {

                        future.completeExceptionally(error);
                    }

                    @Override
                    public void cancelled() {

                        future.cancel(false);
                    }
                });
        return future;
    }

    @Override
    public void close() throws IOException {

        client.close();
    }
}
