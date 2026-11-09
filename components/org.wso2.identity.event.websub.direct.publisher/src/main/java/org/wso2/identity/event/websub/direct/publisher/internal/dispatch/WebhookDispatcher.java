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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.identity.event.delivery.core.http.DeliveryHttpClient;
import org.wso2.identity.event.delivery.core.http.DeliveryOutcome;
import org.wso2.identity.event.delivery.core.http.HttpResult;
import org.wso2.identity.event.delivery.core.http.ResponseClassifier;
import org.wso2.identity.event.delivery.core.retry.RetryAfter;
import org.wso2.identity.event.delivery.core.security.BlockedAddressException;
import org.wso2.identity.event.delivery.core.security.EndpointValidator;
import org.wso2.identity.event.delivery.core.security.InvalidEndpointException;
import org.wso2.identity.event.delivery.core.store.DeliveryStatus;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Makes one direct delivery attempt per event and webhook, and stores every failure for the Retry Worker.
 * <p>
 * Rules (design section 5.1):
 * <ul>
 *     <li>One attempt only; all retries happen in the Retry Worker.</li>
 *     <li>A per-webhook circuit breaker sends events of a failing endpoint straight to the database.</li>
 *     <li>At most {@code maxInFlight} requests run at once; above that, events go straight to the database.</li>
 *     <li>On shutdown, new events and requests still in flight are stored as PENDING with no attempt counted.</li>
 * </ul>
 */
public class WebhookDispatcher implements DeliveryStrategy {

    private static final Log LOG = LogFactory.getLog(WebhookDispatcher.class);

    /**
     * Dispatcher settings.
     */
    public static final class Settings {

        private final int maxInFlight;
        private final Duration firstRetryDelay;

        public Settings(int maxInFlight, Duration firstRetryDelay) {

            this.maxInFlight = maxInFlight;
            this.firstRetryDelay = firstRetryDelay;
        }
    }

    private final DeliveryHttpClient client;
    private final EndpointValidator validator;
    private final ResponseClassifier classifier;
    private final CircuitBreaker circuitBreaker;
    private final FailureStoreWriter writer;
    private final DeliveryObserver observer;
    private final DispatchMetrics metrics;
    private final Executor callbackExecutor;
    private final Settings settings;
    private final Semaphore permits;
    private final Map<DeliveryRequest, CompletableFuture<HttpResult>> inFlight = new ConcurrentHashMap<>();
    private volatile boolean shuttingDown;

    public WebhookDispatcher(DeliveryHttpClient client, EndpointValidator validator, ResponseClassifier classifier,
                             CircuitBreaker circuitBreaker, FailureStoreWriter writer, DeliveryObserver observer,
                             DispatchMetrics metrics, Executor callbackExecutor, Settings settings) {

        this.client = client;
        this.validator = validator;
        this.classifier = classifier;
        this.circuitBreaker = circuitBreaker;
        this.writer = writer;
        this.observer = observer;
        this.metrics = metrics;
        this.callbackExecutor = callbackExecutor;
        this.settings = settings;
        this.permits = new Semaphore(settings.maxInFlight);
    }

    @Override
    public void deliver(DeliveryRequest request) {

        URI endpoint;
        try {
            endpoint = validator.validate(request.getEndpointUrl());
        } catch (InvalidEndpointException e) {
            store(request, DeliveryStatus.FAILED, 0, null, e.getMessage());
            return;
        }
        if (shuttingDown) {
            store(request, DeliveryStatus.PENDING, 0, null, "Not attempted: IS is shutting down.");
            return;
        }
        if (circuitBreaker.isOpen(request.getWebhookId())) {
            metrics.skippedByCircuit();
            store(request, DeliveryStatus.PENDING, 0, null, "Not attempted: the endpoint is failing.");
            return;
        }
        if (!permits.tryAcquire()) {
            metrics.skippedByCapacity();
            store(request, DeliveryStatus.PENDING, 0, null, "Not attempted: too many deliveries in progress.");
            return;
        }
        CompletableFuture<HttpResult> future;
        try {
            future = client.post(endpoint, request.getPayload(), request.getHeaders());
        } catch (RuntimeException e) {
            permits.release();
            LOG.error("Webhook delivery could not be started. Event: " + request.getEventId() + ", webhook: "
                    + request.getWebhookId(), e);
            store(request, DeliveryStatus.PENDING, 0, null, "Not attempted: the HTTP client failed.");
            return;
        }
        inFlight.put(request, future);
        future.whenCompleteAsync((result, error) -> {
            if (inFlight.remove(request) == null) {
                // Shutdown already stored this delivery.
                return;
            }
            permits.release();
            try {
                handle(request, result, error);
            } catch (RuntimeException e) {
                LOG.error("Webhook delivery result could not be handled. Event: " + request.getEventId()
                        + ", webhook: " + request.getWebhookId(), e);
            }
        }, callbackExecutor);
    }

    /**
     * @return Requests currently in flight.
     */
    public int inFlight() {

        return inFlight.size();
    }

    /**
     * Stops direct attempts, waits for requests in flight, and stores the ones that do not finish in time.
     *
     * @param grace Time allowed for requests in flight.
     */
    public void shutdown(Duration grace) {

        shuttingDown = true;
        long deadline = System.nanoTime() + grace.toNanos();
        while (!inFlight.isEmpty() && System.nanoTime() - deadline < 0) {
            try {
                TimeUnit.MILLISECONDS.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        for (DeliveryRequest request : inFlight.keySet()) {
            CompletableFuture<HttpResult> future = inFlight.remove(request);
            if (future != null) {
                permits.release();
                future.cancel(false);
                // The endpoint may have received it; the receiver removes the duplicate by event ID.
                store(request, DeliveryStatus.PENDING, 0, null, "Not finished: IS shut down during the attempt.");
            }
        }
    }

    void handle(DeliveryRequest request, HttpResult result, Throwable error) {

        DeliveryOutcome outcome = error == null ? classifier.classify(result.getStatusCode())
                : classifier.classify(unwrap(error));
        Integer statusCode = error == null ? Integer.valueOf(result.getStatusCode()) : null;
        if (outcome == DeliveryOutcome.DELIVERED) {
            circuitBreaker.recordSuccess(request.getWebhookId());
            metrics.directDelivered();
            observer.onDelivered(request, result.getStatusCode());
            return;
        }
        String reason = reason(result, error);
        metrics.directFailed();
        observer.onAttemptFailed(request, reason);
        if (outcome == DeliveryOutcome.FAILED) {
            boolean blocked = error != null && isBlocked(error);
            store(request, DeliveryStatus.FAILED, blocked ? 0 : 1, statusCode, reason);
            return;
        }
        if (circuitBreaker.recordFailure(request.getWebhookId())) {
            metrics.circuitOpened();
            LOG.warn("Direct deliveries to webhook " + request.getWebhookId() + " are paused on this node after "
                    + "repeated failures. Events go to the retry queue.");
        }
        Duration delay = settings.firstRetryDelay;
        if (result != null) {
            Optional<Duration> requested = RetryAfter.parse(result.getRetryAfter(), Instant.now());
            if (requested.isPresent() && requested.get().compareTo(delay) > 0) {
                delay = requested.get();
            }
        }
        writer.submit(request, DeliveryStatus.PENDING, 1, statusCode, reason, delay);
    }

    private void store(DeliveryRequest request, DeliveryStatus status, int attempts, Integer statusCode,
                       String reason) {

        writer.submit(request, status, attempts, statusCode, reason, settings.firstRetryDelay);
    }

    private static String reason(HttpResult result, Throwable error) {

        if (error == null) {
            return "HTTP " + result.getStatusCode();
        }
        Throwable cause = unwrap(error);
        if (isBlocked(cause)) {
            return "The endpoint resolves to an address that is not allowed for webhooks.";
        }
        // Only the type: messages can contain host names or URLs with query strings.
        return cause.getClass().getSimpleName();
    }

    private static boolean isBlocked(Throwable error) {

        Throwable current = error;
        for (int depth = 0; current != null && depth < 20; depth++) {
            if (current instanceof BlockedAddressException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static Throwable unwrap(Throwable error) {

        if (error instanceof CompletionException && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }
}
