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

import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;

/**
 * Tests for {@link PayloadSigner}. Expected values were computed independently with Python's hmac module.
 */
public class PayloadSignerTest {

    @Test
    public void testKnownVector() {

        String signature = PayloadSigner.sign("key",
                "The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8));

        Assert.assertEquals(signature,
                "sha256=f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }

    @Test
    public void testNonAsciiPayloadIsSignedAsUtf8() {

        // The payload contains characters outside ISO-8859-1. The signature must match a receiver that computes
        // HMAC over the UTF-8 request body.
        byte[] payload = "{\"name\":\"José 山田\"}".getBytes(StandardCharsets.UTF_8);

        Assert.assertEquals(PayloadSigner.sign("s3cr3t", payload),
                "sha256=96efac55c2c5b724a6d4e18f4ccc2e2cf3364d00e0306c3e4a35a56f140864b9");
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testEmptySecretRejected() {

        PayloadSigner.sign("", new byte[]{1});
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testNullSecretRejected() {

        PayloadSigner.sign(null, new byte[]{1});
    }
}
