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

import org.wso2.identity.event.delivery.core.security.BlockedAddressException;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Maps an HTTP status code or a transport error to a {@link DeliveryOutcome}.
 * <p>
 * The default status sets match the current WebSub hub (RETRYABLE_STATUS_CODES and
 * DEAD_LETTER_INCLUDED_STATUS_CODES), so behavior stays the same for receivers. A status in neither set, other than
 * 2xx and 3xx, means the endpoint rejects requests in general.
 */
public final class ResponseClassifier {

    /**
     * Status codes that are retried with backoff.
     */
    public static final Set<Integer> DEFAULT_RETRYABLE_STATUS_CODES =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(408, 429, 500, 502, 503, 504)));

    /**
     * Status codes that fail the message for good.
     */
    public static final Set<Integer> DEFAULT_PERMANENT_STATUS_CODES =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(400, 413, 415, 422)));

    // Guards against a cause chain that loops back on itself.
    private static final int MAX_CAUSE_DEPTH = 20;

    private final Set<Integer> retryableStatusCodes;
    private final Set<Integer> permanentStatusCodes;

    /**
     * Creates a classifier with the default status code sets.
     */
    public ResponseClassifier() {

        this(DEFAULT_RETRYABLE_STATUS_CODES, DEFAULT_PERMANENT_STATUS_CODES);
    }

    /**
     * Creates a classifier with custom status code sets.
     *
     * @param retryableStatusCodes Status codes to retry with backoff.
     * @param permanentStatusCodes Status codes that fail the message for good.
     * @throws IllegalArgumentException If a code is in both sets, or a set contains a 2xx or 3xx code.
     */
    public ResponseClassifier(Set<Integer> retryableStatusCodes, Set<Integer> permanentStatusCodes) {

        Set<Integer> retryable = new HashSet<>(retryableStatusCodes);
        Set<Integer> permanent = new HashSet<>(permanentStatusCodes);
        for (Integer code : retryable) {
            if (permanent.contains(code)) {
                throw new IllegalArgumentException("Status code " + code + " is both retryable and permanent.");
            }
            validateFailureCode(code);
        }
        for (Integer code : permanent) {
            validateFailureCode(code);
        }
        this.retryableStatusCodes = Collections.unmodifiableSet(retryable);
        this.permanentStatusCodes = Collections.unmodifiableSet(permanent);
    }

    /**
     * Classifies an HTTP response status.
     *
     * @param statusCode HTTP status code returned by the endpoint.
     * @return The outcome of the attempt.
     */
    public DeliveryOutcome classify(int statusCode) {

        if (statusCode >= 200 && statusCode < 300) {
            return DeliveryOutcome.DELIVERED;
        }
        if (statusCode >= 300 && statusCode < 400) {
            // Redirects are never followed, so that a redirect cannot lead to an internal address.
            return DeliveryOutcome.FAILED;
        }
        if (retryableStatusCodes.contains(statusCode)) {
            return DeliveryOutcome.RETRY;
        }
        if (permanentStatusCodes.contains(statusCode)) {
            return DeliveryOutcome.FAILED;
        }
        return DeliveryOutcome.ENDPOINT_REJECTED;
    }

    /**
     * Classifies an error raised instead of a response.
     * <p>
     * A blocked address anywhere in the cause chain fails the message. Any other I/O error (timeout, refused
     * connection, TLS failure, DNS failure) is temporary. Anything else is a programming or request error, which a
     * retry would not fix.
     *
     * @param error Error raised by the HTTP client.
     * @return The outcome of the attempt.
     */
    public DeliveryOutcome classify(Throwable error) {

        if (findCause(error, BlockedAddressException.class)) {
            return DeliveryOutcome.FAILED;
        }
        if (findCause(error, IOException.class)) {
            return DeliveryOutcome.RETRY;
        }
        return DeliveryOutcome.FAILED;
    }

    /**
     * @return Status codes retried with backoff.
     */
    public Set<Integer> getRetryableStatusCodes() {

        return retryableStatusCodes;
    }

    /**
     * @return Status codes that fail the message for good.
     */
    public Set<Integer> getPermanentStatusCodes() {

        return permanentStatusCodes;
    }

    private static boolean findCause(Throwable error, Class<? extends Throwable> type) {

        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static void validateFailureCode(Integer code) {

        if (code == null || code < 400 || code > 599) {
            throw new IllegalArgumentException("Only 4xx and 5xx status codes can be configured, got: " + code);
        }
    }
}
