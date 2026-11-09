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

package org.wso2.identity.event.delivery.core.store;

/**
 * State of a stored delivery. A delivered row is deleted, so there is no delivered state.
 */
public enum DeliveryStatus {

    /**
     * Waiting for its next attempt.
     */
    PENDING,

    /**
     * Claimed by a Retry Worker replica, which holds a lease on it.
     */
    IN_PROGRESS,

    /**
     * Failed for good. Kept for the retention period, then purged.
     */
    FAILED,

    /**
     * Not delivered within the retry window. Kept for the retention period, then purged.
     */
    EXPIRED,

    /**
     * Its webhook was deactivated or unsubscribed. Kept for the retention period, then purged.
     */
    CANCELLED
}
