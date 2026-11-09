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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * The AES-256 keys that protect stored deliveries, by key ID.
 * <p>
 * New data is always encrypted with the active key. Older keys stay in the ring so rows written before a rotation can
 * still be read; the key ID is stored with each row. A key can leave the ring once no row uses it, which is at most
 * the retry window plus the retention period after the rotation.
 */
public final class KeyRing {

    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final int AES_256_KEY_BYTES = 32;
    private static final String AES = "AES";

    private final Map<String, SecretKey> keys;
    private final String activeKeyId;

    /**
     * @param rawKeys     Key bytes by key ID. Each key must be 32 bytes.
     * @param activeKeyId ID of the key used for new data. It must be in {@code rawKeys}.
     */
    public KeyRing(Map<String, byte[]> rawKeys, String activeKeyId) {

        if (rawKeys == null || rawKeys.isEmpty()) {
            throw new IllegalArgumentException("At least one delivery encryption key is required.");
        }
        Map<String, SecretKey> parsed = new HashMap<>();
        for (Map.Entry<String, byte[]> entry : rawKeys.entrySet()) {
            String keyId = entry.getKey();
            if (keyId == null || !KEY_ID.matcher(keyId).matches()) {
                throw new IllegalArgumentException("Invalid delivery encryption key ID: " + keyId);
            }
            byte[] raw = entry.getValue();
            if (raw == null || raw.length != AES_256_KEY_BYTES) {
                throw new IllegalArgumentException("Delivery encryption key '" + keyId + "' must be 32 bytes.");
            }
            parsed.put(keyId, new SecretKeySpec(raw, AES));
        }
        if (!parsed.containsKey(activeKeyId)) {
            throw new IllegalArgumentException("The active delivery encryption key '" + activeKeyId
                    + "' is not in the key ring.");
        }
        this.keys = Collections.unmodifiableMap(parsed);
        this.activeKeyId = activeKeyId;
    }

    /**
     * Loads keys from a directory, such as a Kubernetes secret mounted as files.
     * <p>
     * Each file whose name is a valid key ID holds one key as Base64 text. Names starting with a dot are skipped,
     * because Kubernetes adds hidden {@code ..data} entries to secret mounts.
     *
     * @param directory   Directory that holds the key files.
     * @param activeKeyId ID of the key used for new data.
     * @return The loaded key ring.
     * @throws IOException If the directory or a key file cannot be read.
     */
    public static KeyRing fromDirectory(Path directory, String activeKeyId) throws IOException {

        Map<String, byte[]> rawKeys = new HashMap<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                Path fileName = file.getFileName();
                if (fileName == null) {
                    continue;
                }
                String name = fileName.toString();
                if (name.startsWith(".") || !KEY_ID.matcher(name).matches() || !Files.isRegularFile(file)) {
                    continue;
                }
                String encoded = new String(Files.readAllBytes(file), StandardCharsets.US_ASCII).trim();
                try {
                    rawKeys.put(name, Base64.getDecoder().decode(encoded));
                } catch (IllegalArgumentException e) {
                    throw new IOException("Delivery encryption key file '" + name + "' is not valid Base64.", e);
                }
            }
        }
        return new KeyRing(rawKeys, activeKeyId);
    }

    /**
     * @return ID of the key used for new data.
     */
    public String getActiveKeyId() {

        return activeKeyId;
    }

    /**
     * @return The key used for new data.
     */
    SecretKey activeKey() {

        return keys.get(activeKeyId);
    }

    /**
     * @param keyId Key ID stored with a row.
     * @return The key with that ID.
     * @throws DeliveryCryptoException If the key is not in the ring.
     */
    SecretKey key(String keyId) throws DeliveryCryptoException {

        SecretKey key = keys.get(keyId);
        if (key == null) {
            throw new DeliveryCryptoException("Delivery encryption key '" + keyId + "' is not in the key ring.");
        }
        return key;
    }
}
