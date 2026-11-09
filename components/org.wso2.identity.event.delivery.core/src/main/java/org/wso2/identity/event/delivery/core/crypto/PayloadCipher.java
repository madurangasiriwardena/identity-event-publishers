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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.UUID;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts the payload and headers of a stored delivery with AES-256-GCM.
 * <p>
 * Stored format: one format byte, a 12-byte random IV, then the ciphertext with its 16-byte tag. Each call uses a new
 * IV. The caller passes associated data built with {@link #associatedData}, which binds the ciphertext to its row and
 * column, so a value copied into another row or column fails to decrypt.
 */
public final class PayloadCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final byte FORMAT_V1 = 1;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final KeyRing keyRing;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param keyRing Keys to encrypt and decrypt with.
     */
    public PayloadCipher(KeyRing keyRing) {

        this.keyRing = keyRing;
    }

    /**
     * Builds the associated data for one stored value.
     *
     * @param deliveryId ID of the delivery row.
     * @param column     Column that holds the value, for example {@code payload}.
     * @return Associated data to pass to {@link #encrypt} and {@link #decrypt}.
     */
    public static byte[] associatedData(UUID deliveryId, String column) {

        return (deliveryId + "/" + column).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * @return ID of the key that {@link #encrypt} uses. Store it with the row.
     */
    public String getActiveKeyId() {

        return keyRing.getActiveKeyId();
    }

    /**
     * Encrypts a value with the active key.
     *
     * @param plaintext      Value to protect.
     * @param associatedData Data from {@link #associatedData}.
     * @return Stored form of the value.
     * @throws DeliveryCryptoException If encryption fails.
     */
    public byte[] encrypt(byte[] plaintext, byte[] associatedData) throws DeliveryCryptoException {

        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keyRing.activeKey(), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(associatedData);
            byte[] ciphertext = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(1 + IV_BYTES + ciphertext.length)
                    .put(FORMAT_V1).put(iv).put(ciphertext).array();
        } catch (GeneralSecurityException e) {
            throw new DeliveryCryptoException("Delivery data cannot be encrypted.", e);
        }
    }

    /**
     * Decrypts a value.
     *
     * @param keyId          Key ID stored with the row.
     * @param stored         Stored form from {@link #encrypt}.
     * @param associatedData Data from {@link #associatedData}, for the same row and column.
     * @return The original value.
     * @throws DeliveryCryptoException If the key is unknown, or the value was changed or belongs to another row.
     */
    public byte[] decrypt(String keyId, byte[] stored, byte[] associatedData) throws DeliveryCryptoException {

        if (stored == null || stored.length < 1 + IV_BYTES + TAG_BITS / 8 || stored[0] != FORMAT_V1) {
            throw new DeliveryCryptoException("Stored delivery data has an unknown format.");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keyRing.key(keyId), new GCMParameterSpec(TAG_BITS, stored, 1, IV_BYTES));
            cipher.updateAAD(associatedData);
            return cipher.doFinal(stored, 1 + IV_BYTES, stored.length - 1 - IV_BYTES);
        } catch (AEADBadTagException e) {
            throw new DeliveryCryptoException("Stored delivery data failed the integrity check.", e);
        } catch (GeneralSecurityException e) {
            throw new DeliveryCryptoException("Stored delivery data cannot be decrypted.", e);
        }
    }
}
