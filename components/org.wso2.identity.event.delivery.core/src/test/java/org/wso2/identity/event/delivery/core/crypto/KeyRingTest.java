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
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Tests for {@link KeyRing}.
 */
public class KeyRingTest {

    private Path directory;

    @BeforeMethod
    public void createDirectory() throws IOException {

        directory = Files.createTempDirectory("keyring");
    }

    @AfterMethod
    public void deleteDirectory() throws IOException {

        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    private void writeKey(String name, byte[] key) throws IOException {

        Files.write(directory.resolve(name),
                (Base64.getEncoder().encodeToString(key) + "\n").getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    public void testLoadsKeysFromKubernetesStyleMount() throws IOException, DeliveryCryptoException {

        writeKey("key-2026-11", PayloadCipherTest.key(7));
        writeKey("key-2026-05", PayloadCipherTest.key(8));
        // Kubernetes adds hidden entries to secret mounts; they must be skipped.
        Files.createDirectory(directory.resolve("..2026_11_06_10_00_00.123"));
        Files.write(directory.resolve(".hidden"), "not a key".getBytes(StandardCharsets.US_ASCII));
        Files.write(directory.resolve("README.txt"), "not a key".getBytes(StandardCharsets.US_ASCII));

        KeyRing ring = KeyRing.fromDirectory(directory, "key-2026-11");

        Assert.assertEquals(ring.getActiveKeyId(), "key-2026-11");
        Assert.assertEquals(ring.activeKey().getEncoded(), PayloadCipherTest.key(7));
        Assert.assertEquals(ring.key("key-2026-05").getEncoded(), PayloadCipherTest.key(8));
    }

    @Test(expectedExceptions = IOException.class)
    public void testInvalidBase64Fails() throws IOException {

        Files.write(directory.resolve("k1"), "%%%".getBytes(StandardCharsets.US_ASCII));

        KeyRing.fromDirectory(directory, "k1");
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testMissingActiveKeyFails() throws IOException {

        writeKey("k1", PayloadCipherTest.key(1));

        KeyRing.fromDirectory(directory, "k2");
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testEmptyDirectoryFails() throws IOException {

        KeyRing.fromDirectory(directory, "k1");
    }

    @DataProvider
    public Object[][] invalidKeys() {

        return new Object[][]{
                {"k1", new byte[16]},
                {"k1", null},
                {"bad id", new byte[32]},
                {"", new byte[32]},
        };
    }

    @Test(dataProvider = "invalidKeys", expectedExceptions = IllegalArgumentException.class)
    public void testRejectsInvalidKey(String keyId, byte[] key) {

        Map<String, byte[]> keys = new HashMap<>();
        keys.put(keyId, key);
        new KeyRing(keys, keyId);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testRejectsEmptyRing() {

        new KeyRing(Collections.<String, byte[]>emptyMap(), "k1");
    }

    @Test(expectedExceptions = DeliveryCryptoException.class)
    public void testUnknownKeyId() throws DeliveryCryptoException {

        new KeyRing(Collections.singletonMap("k1", PayloadCipherTest.key(1)), "k1").key("k2");
    }
}
