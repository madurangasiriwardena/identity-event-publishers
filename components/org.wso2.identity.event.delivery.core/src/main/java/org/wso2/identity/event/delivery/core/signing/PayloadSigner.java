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

package org.wso2.identity.event.delivery.core.signing;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Computes the {@code X-Hub-Signature} value for a webhook payload.
 * <p>
 * The format is {@code sha256=<lowercase hex HMAC-SHA256>}, keyed with the UTF-8 bytes of the webhook secret. This is
 * the format the current WebSub hub sends and the product documentation describes. The signature covers the exact
 * bytes sent in the request body, so callers must sign and send the same byte array.
 */
public final class PayloadSigner {

    /**
     * Prefix that names the algorithm in the header value.
     */
    public static final String SHA256_PREFIX = "sha256=";

    private static final String HMAC_SHA256 = "HmacSHA256";

    private PayloadSigner() {

    }

    /**
     * @param secret  Webhook secret.
     * @param payload Exact request body bytes.
     * @return Header value, for example {@code sha256=5f1c...}.
     */
    public static String sign(String secret, byte[] payload) {

        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("A webhook secret is required to sign a payload.");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return SHA256_PREFIX + HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is a mandatory JCA algorithm, so this means a broken runtime.
            throw new IllegalStateException("HmacSHA256 is not available.", e);
        }
    }
}
