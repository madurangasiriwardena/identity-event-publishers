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

package org.wso2.identity.event.delivery.core.retry;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Parses the HTTP {@code Retry-After} header that endpoints send with 429 and 503 responses.
 */
public final class RetryAfter {

    private RetryAfter() {

    }

    /**
     * Parses a {@code Retry-After} value, which is either a number of seconds or an HTTP date.
     *
     * @param headerValue Header value, or null if the header is absent.
     * @param now         Current time, used to turn an HTTP date into a delay.
     * @return The requested delay, never negative. Empty if the header is absent or cannot be parsed.
     */
    public static Optional<Duration> parse(String headerValue, Instant now) {

        if (headerValue == null) {
            return Optional.empty();
        }
        String value = headerValue.trim();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        if (value.chars().allMatch(Character::isDigit)) {
            try {
                return Optional.of(Duration.ofSeconds(Long.parseLong(value)));
            } catch (NumberFormatException e) {
                // More digits than a long can hold; treat the header as unusable.
                return Optional.empty();
            }
        }
        try {
            Instant retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration delay = Duration.between(now, retryAt);
            return Optional.of(delay.isNegative() ? Duration.ZERO : delay);
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
