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

package org.wso2.identity.event.delivery.core.http;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.protocol.AbstractAsyncResponseConsumer;
import org.apache.http.protocol.HttpContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Reads a response and keeps at most a fixed number of body bytes.
 * <p>
 * Webhook endpoints are outside our control. The default consumer buffers the whole body in memory, so a large or
 * endless body could exhaust the heap. This consumer reads and drops everything after the limit. Deliveries keep no
 * body at all; verification keeps enough to compare the challenge.
 */
public final class LimitedResponseConsumer extends AbstractAsyncResponseConsumer<HttpResult> {

    private static final String RETRY_AFTER = "Retry-After";
    private static final int READ_BUFFER_BYTES = 8192;

    private final int maxBodyBytes;
    private final ByteBuffer readBuffer = ByteBuffer.allocate(READ_BUFFER_BYTES);
    private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
    private int statusCode;
    private String retryAfter;

    /**
     * @param maxBodyBytes Number of body bytes to keep. Zero keeps none.
     */
    public LimitedResponseConsumer(int maxBodyBytes) {

        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    protected void onResponseReceived(HttpResponse response) {

        statusCode = response.getStatusLine().getStatusCode();
        Header header = response.getFirstHeader(RETRY_AFTER);
        retryAfter = header == null ? null : header.getValue();
    }

    @Override
    protected void onContentReceived(ContentDecoder decoder, IOControl ioControl) throws IOException {

        int read;
        while ((read = decoder.read(readBuffer)) > 0) {
            readBuffer.flip();
            int keep = Math.min(read, maxBodyBytes - kept.size());
            if (keep > 0) {
                kept.write(readBuffer.array(), 0, keep);
            }
            readBuffer.clear();
        }
    }

    @Override
    protected void onEntityEnclosed(HttpEntity entity, ContentType contentType) {

        // Nothing to prepare; content is handled as it arrives.
    }

    @Override
    protected HttpResult buildResult(HttpContext context) {

        return new HttpResult(statusCode, retryAfter, kept.toByteArray());
    }

    @Override
    protected void releaseResources() {

        readBuffer.clear();
    }
}
