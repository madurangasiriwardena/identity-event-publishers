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

package org.wso2.identity.event.websub.direct.publisher.constant;

/**
 * Constants of the WebSub direct adapter.
 */
public final class WebSubDirectConstants {

    /**
     * Adapter name, as used in identity-outbound-adapter.properties ({@code adapter.websubdirect.*}).
     */
    public static final String ADAPTER_NAME = "websubdirect";

    private WebSubDirectConstants() {

    }

    /**
     * WebSub topic format. It matches the WebSubHub adapter, so topics and subscriptions are interchangeable.
     */
    public static final class Topic {

        public static final String SEPARATOR = ".";
        public static final String SCHEMA = "schema";
        public static final String EVENT = "event";

        private Topic() {

        }
    }

    /**
     * WebSub verification request parameters.
     */
    public static final class Hub {

        public static final String MODE = "hub.mode";
        public static final String TOPIC = "hub.topic";
        public static final String CHALLENGE = "hub.challenge";
        public static final String LEASE_SECONDS = "hub.lease_seconds";
        public static final String MODE_SUBSCRIBE = "subscribe";
        public static final String MODE_UNSUBSCRIBE = "unsubscribe";

        private Hub() {

        }
    }

    /**
     * Diagnostic log names.
     */
    public static final class Log {

        public static final String COMPONENT = "websub-direct-adapter";
        public static final String ACTION_PUBLISH = "publish-event";
        public static final String ACTION_SUBSCRIBE = "subscribe";
        public static final String ACTION_UNSUBSCRIBE = "unsubscribe";
        public static final String INPUT_EVENT_ID = "eventId";
        public static final String INPUT_WEBHOOK_ID = "webhookId";
        public static final String INPUT_EVENT_TYPE = "eventType";
        public static final String INPUT_TOPIC = "topic";

        private Log() {

        }
    }
}
