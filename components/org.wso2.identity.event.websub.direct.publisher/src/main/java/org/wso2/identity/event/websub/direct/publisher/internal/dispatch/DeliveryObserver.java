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

import org.wso2.identity.event.delivery.core.store.DeliveryStatus;

/**
 * Receives the result of each delivery step, for logs and metrics. Implementations must be fast and must not throw,
 * because they run on delivery threads.
 * <p>
 * Keeping these calls behind an interface keeps the dispatcher free of Carbon calls, so it can be tested alone.
 */
public interface DeliveryObserver {

    /**
     * The direct attempt succeeded.
     */
    void onDelivered(DeliveryRequest request, int statusCode);

    /**
     * The direct attempt failed.
     *
     * @param reason Short reason, for example "HTTP 503" or "SocketTimeoutException".
     */
    void onAttemptFailed(DeliveryRequest request, String reason);

    /**
     * The delivery was written to the database for the Retry Worker.
     */
    void onStored(DeliveryRequest request, DeliveryStatus status);

    /**
     * The delivery was lost. This must alert.
     *
     * @param reason Why it could not be stored.
     */
    void onDropped(DeliveryRequest request, String reason);
}
