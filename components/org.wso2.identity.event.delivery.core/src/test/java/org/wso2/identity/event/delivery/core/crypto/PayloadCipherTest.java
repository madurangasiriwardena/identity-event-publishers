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

import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tests for {@link PayloadCipher}.
 */
public class PayloadCipherTest {

    private static final byte[] PAYLOAD = "{\"jti\":\"1\",\"user\":\"José\"}".getBytes(StandardCharsets.UTF_8);

    static byte[] key(int fill) {

        byte[] key = new byte[32];
        Arrays.fill(key, (byte) fill);
        return key;
    }

    private static KeyRing ring(String activeKeyId) {

        Map<String, byte[]> keys = new HashMap<>();
        keys.put("k1", key(1));
        keys.put("k2", key(2));
        return new KeyRing(keys, activeKeyId);
    }

    @Test
    public void testRoundTrip() throws DeliveryCryptoException {

        PayloadCipher cipher = new PayloadCipher(ring("k1"));
        UUID id = UUID.randomUUID();
        byte[] aad = PayloadCipher.associatedData(id, "payload");

        byte[] stored = cipher.encrypt(PAYLOAD, aad);

        Assert.assertEquals(cipher.getActiveKeyId(), "k1");
        Assert.assertEquals(stored.length, 1 + 12 + PAYLOAD.length + 16);
        Assert.assertEquals(cipher.decrypt("k1", stored, aad), PAYLOAD);
    }

    @Test
    public void testEachEncryptionUsesNewIv() throws DeliveryCryptoException {

        PayloadCipher cipher = new PayloadCipher(ring("k1"));
        byte[] aad = PayloadCipher.associatedData(UUID.randomUUID(), "payload");

        Assert.assertNotEquals(cipher.encrypt(PAYLOAD, aad), cipher.encrypt(PAYLOAD, aad));
    }

    @Test
    public void testOldKeyStillDecryptsAfterRotation() throws DeliveryCryptoException {

        byte[] aad = PayloadCipher.associatedData(UUID.randomUUID(), "headers");
        byte[] stored = new PayloadCipher(ring("k1")).encrypt(PAYLOAD, aad);

        PayloadCipher rotated = new PayloadCipher(ring("k2"));

        Assert.assertEquals(rotated.getActiveKeyId(), "k2");
        Assert.assertEquals(rotated.decrypt("k1", stored, aad), PAYLOAD);
    }

    @Test(expectedExceptions = DeliveryCryptoException.class)
    public void testValueMovedToAnotherRowFails() throws DeliveryCryptoException {

        PayloadCipher cipher = new PayloadCipher(ring("k1"));
        byte[] stored = cipher.encrypt(PAYLOAD, PayloadCipher.associatedData(UUID.randomUUID(), "payload"));

        cipher.decrypt("k1", stored, PayloadCipher.associatedData(UUID.randomUUID(), "payload"));
    }

    @Test(expectedExceptions = DeliveryCryptoException.class)
    public void testValueMovedToAnotherColumnFails() throws DeliveryCryptoException {

        PayloadCipher cipher = new PayloadCipher(ring("k1"));
        UUID id = UUID.randomUUID();
        byte[] stored = cipher.encrypt(PAYLOAD, PayloadCipher.associatedData(id, "payload"));

        cipher.decrypt("k1", stored, PayloadCipher.associatedData(id, "headers"));
    }

    @Test(expectedExceptions = DeliveryCryptoException.class)
    public void testTamperedValueFails() throws DeliveryCryptoException {

        PayloadCipher cipher = new PayloadCipher(ring("k1"));
        byte[] aad = PayloadCipher.associatedData(UUID.randomUUID(), "payload");
        byte[] stored = cipher.encrypt(PAYLOAD, aad);
        stored[stored.length - 1] ^= 0x01;

        cipher.decrypt("k1", stored, aad);
    }

    @Test(expectedExceptions = DeliveryCryptoException.class)
    public void testWrongKeyFails() throws DeliveryCryptoException {

        PayloadCipher cipher = new PayloadCipher(ring("k1"));
        byte[] aad = PayloadCipher.associatedData(UUID.randomUUID(), "payload");

        cipher.decrypt("k2", cipher.encrypt(PAYLOAD, aad), aad);
    }

    @Test(expectedExceptions = DeliveryCryptoException.class)
    public void testUnknownKeyFails() throws DeliveryCryptoException {

        PayloadCipher cipher = new PayloadCipher(ring("k1"));
        byte[] aad = PayloadCipher.associatedData(UUID.randomUUID(), "payload");

        cipher.decrypt("k9", cipher.encrypt(PAYLOAD, aad), aad);
    }

    @Test(expectedExceptions = DeliveryCryptoException.class)
    public void testUnknownFormatFails() throws DeliveryCryptoException {

        PayloadCipher cipher = new PayloadCipher(ring("k1"));
        byte[] aad = PayloadCipher.associatedData(UUID.randomUUID(), "payload");
        byte[] stored = cipher.encrypt(PAYLOAD, aad);
        stored[0] = 2;

        cipher.decrypt("k1", stored, aad);
    }

    @Test(expectedExceptions = DeliveryCryptoException.class)
    public void testTooShortValueFails() throws DeliveryCryptoException {

        new PayloadCipher(ring("k1")).decrypt("k1", new byte[]{1, 2, 3}, new byte[0]);
    }

    @Test(expectedExceptions = DeliveryCryptoException.class)
    public void testNullValueFails() throws DeliveryCryptoException {

        new PayloadCipher(ring("k1")).decrypt("k1", null, new byte[0]);
    }
}
