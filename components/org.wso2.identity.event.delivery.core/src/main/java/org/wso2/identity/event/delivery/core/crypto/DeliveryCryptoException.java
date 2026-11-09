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

package org.wso2.identity.event.delivery.core.crypto;

/**
 * Raised when stored delivery data cannot be encrypted or decrypted, for example because the key is unknown or the
 * data was changed.
 */
public class DeliveryCryptoException extends Exception {

    private static final long serialVersionUID = 7391504426138571036L;

    /**
     * @param message Description of the failure. It never contains key material or plaintext.
     */
    public DeliveryCryptoException(String message) {

        super(message);
    }

    /**
     * @param message Description of the failure.
     * @param cause   Underlying error.
     */
    public DeliveryCryptoException(String message, Throwable cause) {

        super(message, cause);
    }
}
