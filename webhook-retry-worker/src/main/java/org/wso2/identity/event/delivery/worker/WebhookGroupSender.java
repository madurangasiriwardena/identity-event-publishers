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

import org.wso2.identity.event.delivery.core.http.DeliveryHttpClient;
import org.wso2.identity.event.delivery.core.http.DeliveryOutcome;
import org.wso2.identity.event.delivery.core.http.HttpResult;
import org.wso2.identity.event.delivery.core.http.ResponseClassifier;
import org.wso2.identity.event.delivery.core.retry.BackoffPolicy;
import org.wso2.identity.event.delivery.core.retry.RetryAfter;
import org.wso2.identity.event.delivery.core.security.BlockedAddressException;
import org.wso2.identity.event.delivery.core.security.EndpointValidator;
import org.wso2.identity.event.delivery.core.security.InvalidEndpointException;
import org.wso2.identity.event.delivery.core.signing.DeliveryHeaders;
import org.wso2.identity.event.delivery.core.store.ClaimedDelivery;
import org.wso2.identity.event.delivery.core.store.DeliveryContent;
import org.wso2.identity.event.delivery.core.store.DeliveryQueue;
import org.wso2.identity.event.delivery.core.store.DeliveryStatus;
import org.wso2.identity.event.delivery.core.store.DeliveryStoreException;
import org.wso2.identity.event.delivery.core.store.InvalidDeliveryException;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends the claimed rows of one webhook, one after another.
 * <p>
 * Rows of one webhook are sent in order. After the first endpoint failure, the remaining rows are released unsent:
 * the webhook's next attempt time is now in the future, so they would fail too. This also makes the first row after
 * a failure a probe: if it fails, nothing else is sent. Rows are also released when the lease is about to end or the
 * worker stops.
 */
final class WebhookGroupSender {

    private static final Logger LOG = Logger.getLogger(WebhookGroupSender.class.getName());

    private final DeliveryQueue queue;
    private final DeliveryHttpClient client;
    private final EndpointValidator validator;
    private final ResponseClassifier classifier;
    private final BackoffPolicy backoff;
    private final Duration endpointRejectMinInterval;
    private final String workerId;
    private final WorkerMetrics metrics;
    private final AtomicBoolean stopping;
    private final Clock clock;

    WebhookGroupSender(DeliveryQueue queue, DeliveryHttpClient client, EndpointValidator validator,
                       ResponseClassifier classifier, BackoffPolicy backoff, Duration endpointRejectMinInterval,
                       String workerId, WorkerMetrics metrics, AtomicBoolean stopping, Clock clock) {

        this.queue = queue;
        this.client = client;
        this.validator = validator;
        this.classifier = classifier;
        this.backoff = backoff;
        this.endpointRejectMinInterval = endpointRejectMinInterval;
        this.workerId = workerId;
        this.metrics = metrics;
        this.stopping = stopping;
        this.clock = clock;
    }

    /**
     * @param rows          Claimed rows of one webhook, oldest first.
     * @param sendDeadline  No new attempt starts after this time, so results are recorded before the lease ends.
     */
    void send(List<ClaimedDelivery> rows, Instant sendDeadline) {

        boolean endpointFailed = false;
        for (ClaimedDelivery row : rows) {
            try {
                if (endpointFailed) {
                    release(row, "an earlier delivery to this endpoint failed in this batch");
                } else if (stopping.get()) {
                    release(row, "the worker is stopping");
                } else if (clock.instant().isAfter(sendDeadline)) {
                    release(row, "the lease is about to end");
                } else {
                    endpointFailed = !sendOne(row);
                }
            } catch (DeliveryStoreException | RuntimeException e) {
                // The lease reaper returns the row to PENDING if its result could not be recorded.
                LOG.log(Level.WARNING, "Result of delivery " + row.getId() + " for webhook " + row.getWebhookId()
                        + " could not be recorded.", e);
            }
        }
    }

    /**
     * @return False if the endpoint failed, so the remaining rows of this webhook should not be sent.
     */
    private boolean sendOne(ClaimedDelivery row) throws DeliveryStoreException {

        DeliveryContent content;
        try {
            content = queue.open(row);
        } catch (InvalidDeliveryException e) {
            LOG.severe("Delivery " + row.getId() + " cannot be read (" + e.getMessage() + "). It fails "
                    + "without an attempt.");
            fail(row, false, null, "Stored delivery data cannot be read.");
            return true;
        }
        URI endpoint;
        try {
            endpoint = validator.validate(row.getEndpointUrl());
        } catch (InvalidEndpointException e) {
            fail(row, false, null, e.getMessage());
            return true;
        }
        int attempt = row.getAttemptCount() + 1;
        Map<String, String> headers = new LinkedHashMap<>(content.getHeaders());
        headers.put(DeliveryHeaders.ATTEMPT, String.valueOf(attempt));
        LOG.fine(() -> "Sending " + describe(row) + ", attempt " + attempt
                + (row.getEndpointFailures() > 0 ? ", probing an endpoint with " + row.getEndpointFailures()
                + " recent failures" : "") + ".");
        long started = System.nanoTime();
        HttpResult result = null;
        Throwable error = null;
        try {
            result = client.post(endpoint, content.getPayload(), headers).get();
        } catch (ExecutionException e) {
            error = e.getCause() == null ? e : e.getCause();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            release(row, "the worker was interrupted");
            return true;
        }
        DeliveryOutcome outcome = error == null ? classifier.classify(result.getStatusCode())
                : classifier.classify(error);
        Integer statusCode = result == null ? null : result.getStatusCode();
        String reason = reason(result, error);
        long millis = (System.nanoTime() - started) / 1_000_000;
        LOG.fine(() -> "Attempt " + attempt + " of " + describe(row) + ": " + reason + " in " + millis + " ms, "
                + "outcome " + outcome + ".");
        switch (outcome) {
            case DELIVERED:
                if (queue.markDelivered(row.getId(), workerId)) {
                    metrics.delivered();
                    LOG.fine(() -> "Delivered " + describe(row) + "; row removed.");
                } else {
                    leaseLost(row);
                }
                queue.recordEndpointSuccess(row.getWebhookId());
                return true;
            case FAILED:
                fail(row, !(error != null && isBlocked(error)), statusCode, reason);
                return true;
            default:
                retry(row, outcome, result, statusCode, reason);
                return false;
        }
    }

    private void retry(ClaimedDelivery row, DeliveryOutcome outcome, HttpResult result, Integer statusCode,
                       String reason) throws DeliveryStoreException {

        Duration requested = result == null ? null
                : RetryAfter.parse(result.getRetryAfter(), clock.instant()).orElse(null);
        AtomicReference<Duration> delay = new AtomicReference<>();
        int failures = queue.recordEndpointFailure(row.getWebhookId(), row.getTenantId(), statusCode, count -> {
            delay.set(pause(count, outcome, requested));
            return delay.get();
        });
        LOG.fine(() -> "Webhook " + row.getWebhookId() + " next attempt in " + delay.get().getSeconds()
                + " s after " + failures + " consecutive failures"
                + (requested != null ? " (Retry-After: " + requested.getSeconds() + " s)" : "") + ".");
        Optional<DeliveryStatus> status = queue.reschedule(row.getId(), workerId, delay.get(), statusCode, reason);
        if (!status.isPresent()) {
            leaseLost(row);
        } else if (status.get() == DeliveryStatus.EXPIRED) {
            metrics.expired(1);
            LOG.fine(() -> describe(row) + " expired: its retry window ends before the next attempt.");
        } else {
            metrics.retried();
            LOG.fine(() -> describe(row) + " scheduled for retry in " + delay.get().getSeconds() + " s.");
        }
    }

    Duration pause(int failures, DeliveryOutcome outcome, Duration requested) {

        Duration delay = backoff.delayAfter(failures);
        if (outcome == DeliveryOutcome.ENDPOINT_REJECTED && delay.compareTo(endpointRejectMinInterval) < 0) {
            delay = endpointRejectMinInterval;
        }
        if (requested != null) {
            // Respect Retry-After, but never park an endpoint for longer than the maximum backoff.
            Duration capped = requested.compareTo(backoff.getMaxInterval()) > 0 ? backoff.getMaxInterval()
                    : requested;
            if (capped.compareTo(delay) > 0) {
                delay = capped;
            }
        }
        return delay;
    }

    private void fail(ClaimedDelivery row, boolean attemptMade, Integer statusCode, String reason)
            throws DeliveryStoreException {

        if (queue.markFailed(row.getId(), workerId, attemptMade, statusCode, reason)) {
            metrics.failed();
            LOG.fine(() -> describe(row) + " failed for good and will not be retried: " + reason);
        } else {
            leaseLost(row);
        }
    }

    private void release(ClaimedDelivery row, String why) throws DeliveryStoreException {

        if (queue.release(row.getId(), workerId)) {
            metrics.released();
            LOG.fine(() -> "Released " + describe(row) + " unsent: " + why + ".");
        } else {
            leaseLost(row);
        }
    }

    private void leaseLost(ClaimedDelivery row) {

        metrics.leaseLost();
        LOG.fine(() -> "Result of " + describe(row) + " not recorded: this worker no longer holds its lease, or the "
                + "row was cancelled.");
    }

    /**
     * Identifiers only: never the endpoint URL, payload or headers.
     */
    private static String describe(ClaimedDelivery row) {

        return "delivery " + row.getId() + " (event " + row.getEventId() + ", webhook " + row.getWebhookId() + ")";
    }

    private static String reason(HttpResult result, Throwable error) {

        if (error == null) {
            return "HTTP " + result.getStatusCode();
        }
        if (isBlocked(error)) {
            return "The endpoint resolves to an address that is not allowed for webhooks.";
        }
        // Only the type: messages can contain host names or URLs with query strings.
        return error.getClass().getSimpleName();
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
}
