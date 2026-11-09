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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the HTTP headers of a webhook delivery, and encodes them for storage with a failed delivery.
 * <p>
 * Identity Server builds the headers once, at event time. The Retry Worker sends the stored headers unchanged, so it
 * never needs the webhook secret.
 */
public final class DeliveryHeaders {

    /**
     * HMAC signature of the body. Same name and format as the current WebSub hub.
     */
    public static final String SIGNATURE = "X-Hub-Signature";

    /**
     * WebSub links to the hub and the topic. Receivers can route by topic.
     */
    public static final String LINK = "Link";

    /**
     * Event ID (the SET {@code jti}), stable across attempts. Receivers use it to remove duplicates.
     */
    public static final String MESSAGE_ID = "x-hub-messageId";

    /**
     * Attempt number, starting at 1. Informational only, so it is added per attempt and never stored.
     */
    public static final String ATTEMPT = "X-Webhook-Attempt";

    /**
     * Content type of the body.
     */
    public static final String CONTENT_TYPE = "Content-Type";

    /**
     * Content type of every webhook payload.
     */
    public static final String APPLICATION_JSON = "application/json";

    private static final int FORMAT_VERSION = 1;
    private static final int MAX_HEADERS = 64;

    private DeliveryHeaders() {

    }

    /**
     * Builds the stored headers of one delivery.
     *
     * @param hubUrl    Hub URL used in the {@code Link} header.
     * @param topic     WebSub topic of the event channel.
     * @param eventId   SET {@code jti} of the event.
     * @param signature Value from {@link PayloadSigner#sign}, or null if the webhook has no secret.
     * @return Header names and values in sending order.
     */
    public static Map<String, String> build(String hubUrl, String topic, String eventId, String signature) {

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(CONTENT_TYPE, APPLICATION_JSON);
        headers.put(LINK, "<" + hubUrl + ">; rel=\"hub\", <" + topic + ">; rel=\"self\"");
        headers.put(MESSAGE_ID, eventId);
        if (signature != null) {
            headers.put(SIGNATURE, signature);
        }
        return Collections.unmodifiableMap(headers);
    }

    /**
     * Encodes headers into bytes for storage.
     *
     * @param headers Header names and values.
     * @return Encoded headers.
     */
    public static byte[] encode(Map<String, String> headers) {

        if (headers.size() > MAX_HEADERS) {
            throw new IllegalArgumentException("Too many headers: " + headers.size());
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(FORMAT_VERSION);
            out.writeInt(headers.size());
            for (Map.Entry<String, String> header : headers.entrySet()) {
                out.writeUTF(header.getKey());
                out.writeUTF(header.getValue());
            }
        } catch (IOException e) {
            // Writing to memory does not fail, except for a value longer than writeUTF allows.
            throw new IllegalArgumentException("Headers cannot be encoded.", e);
        }
        return bytes.toByteArray();
    }

    /**
     * Decodes headers produced by {@link #encode}.
     *
     * @param encoded Encoded headers.
     * @return Header names and values in their original order.
     */
    public static Map<String, String> decode(byte[] encoded) {

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            int version = in.readInt();
            if (version != FORMAT_VERSION) {
                throw new IllegalArgumentException("Unknown header format version: " + version);
            }
            int count = in.readInt();
            if (count < 0 || count > MAX_HEADERS) {
                throw new IllegalArgumentException("Invalid header count: " + count);
            }
            Map<String, String> headers = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                headers.put(in.readUTF(), in.readUTF());
            }
            if (in.available() > 0) {
                throw new IllegalArgumentException("Unexpected bytes after the encoded headers.");
            }
            return Collections.unmodifiableMap(headers);
        } catch (IOException e) {
            throw new IllegalArgumentException("Stored headers are truncated or corrupt.", e);
        }
    }
}
