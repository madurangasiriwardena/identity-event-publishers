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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Tests for {@link DeliveryHeaders}.
 */
public class DeliveryHeadersTest {

    private static final String HUB = "https://hub.asgardeo.io";
    private static final String TOPIC = "myorg.dcbd6baa-40c7-4eb9-9c2e-19b868d0e266.schema.wso2.v1.event.login";
    private static final String JTI = "051f0c37-b689-44d4-b7d2-29b980ece273";

    @Test
    public void testBuildMatchesCurrentHubHeaders() {

        Map<String, String> headers = DeliveryHeaders.build(HUB, TOPIC, JTI, "sha256=abc");

        Assert.assertEquals(new ArrayList<>(headers.keySet()), Arrays.asList(
                DeliveryHeaders.CONTENT_TYPE, DeliveryHeaders.LINK, DeliveryHeaders.MESSAGE_ID,
                DeliveryHeaders.SIGNATURE));
        Assert.assertEquals(headers.get(DeliveryHeaders.CONTENT_TYPE), "application/json");
        Assert.assertEquals(headers.get(DeliveryHeaders.LINK),
                "<https://hub.asgardeo.io>; rel=\"hub\", <" + TOPIC + ">; rel=\"self\"");
        Assert.assertEquals(headers.get(DeliveryHeaders.MESSAGE_ID), JTI);
        Assert.assertEquals(headers.get(DeliveryHeaders.SIGNATURE), "sha256=abc");
    }

    @Test
    public void testBuildWithoutSecretHasNoSignature() {

        Assert.assertFalse(DeliveryHeaders.build(HUB, TOPIC, JTI, null).containsKey(DeliveryHeaders.SIGNATURE));
    }

    @Test
    public void testEncodeDecodeRoundTripKeepsOrder() {

        Map<String, String> headers = DeliveryHeaders.build(HUB, TOPIC, JTI, "sha256=abc");

        Map<String, String> decoded = DeliveryHeaders.decode(DeliveryHeaders.encode(headers));

        Assert.assertEquals(decoded, headers);
        Assert.assertEquals(new ArrayList<>(decoded.keySet()), new ArrayList<>(headers.keySet()));
    }

    @Test
    public void testRoundTripNonAsciiValue() {

        Map<String, String> headers = new HashMap<>();
        headers.put("X-Note", "café 山");

        Assert.assertEquals(DeliveryHeaders.decode(DeliveryHeaders.encode(headers)), headers);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testDecodeRejectsTruncatedData() {

        byte[] encoded = DeliveryHeaders.encode(DeliveryHeaders.build(HUB, TOPIC, JTI, null));

        DeliveryHeaders.decode(Arrays.copyOf(encoded, encoded.length - 3));
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testDecodeRejectsTrailingBytes() {

        byte[] encoded = DeliveryHeaders.encode(DeliveryHeaders.build(HUB, TOPIC, JTI, null));

        DeliveryHeaders.decode(Arrays.copyOf(encoded, encoded.length + 1));
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testDecodeRejectsUnknownVersion() {

        DeliveryHeaders.decode(new byte[]{0, 0, 0, 9, 0, 0, 0, 0});
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testDecodeRejectsNegativeCount() {

        DeliveryHeaders.decode(new byte[]{0, 0, 0, 1, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testEncodeRejectsTooManyHeaders() {

        Map<String, String> headers = new HashMap<>();
        for (int i = 0; i < 65; i++) {
            headers.put("X-H" + i, "v");
        }
        DeliveryHeaders.encode(headers);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testEncodeRejectsOversizedValue() {

        char[] big = new char[70000];
        Arrays.fill(big, 'a');
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Big", new String(big));

        DeliveryHeaders.encode(headers);
    }
}
