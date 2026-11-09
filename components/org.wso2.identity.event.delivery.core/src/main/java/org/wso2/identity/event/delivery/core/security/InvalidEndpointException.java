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

package org.wso2.identity.event.delivery.core.security;

/**
 * Raised when a webhook URL is not acceptable as a delivery target.
 */
public class InvalidEndpointException extends Exception {

    private static final long serialVersionUID = -2903476871264507790L;

    /**
     * @param message Reason the URL is rejected. It never contains the URL itself, which can hold secrets.
     */
    public InvalidEndpointException(String message) {

        super(message);
    }

    /**
     * @param message Reason the URL is rejected.
     * @param cause   Underlying parse error.
     */
    public InvalidEndpointException(String message, Throwable cause) {

        super(message, cause);
    }
}
