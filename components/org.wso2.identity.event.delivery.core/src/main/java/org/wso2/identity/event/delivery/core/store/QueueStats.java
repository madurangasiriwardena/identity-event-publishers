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

import java.time.Duration;

/**
 * Size and age of the retry queue, for metrics.
 */
public final class QueueStats {

    private final long pending;
    private final long due;
    private final long inProgress;
    private final Duration oldestPendingAge;

    QueueStats(long pending, long due, long inProgress, Duration oldestPendingAge) {

        this.pending = pending;
        this.due = due;
        this.inProgress = inProgress;
        this.oldestPendingAge = oldestPendingAge;
    }

    /**
     * @return Rows waiting for a later attempt, due or not.
     */
    public long getPending() {

        return pending;
    }

    /**
     * @return Pending rows whose webhook has no next attempt time or one that has passed. These can be claimed now.
     */
    public long getDue() {

        return due;
    }

    /**
     * @return Rows claimed by a worker replica.
     */
    public long getInProgress() {

        return inProgress;
    }

    /**
     * @return Age of the oldest pending row, or zero if there is none.
     */
    public Duration getOldestPendingAge() {

        return oldestPendingAge;
    }
}
