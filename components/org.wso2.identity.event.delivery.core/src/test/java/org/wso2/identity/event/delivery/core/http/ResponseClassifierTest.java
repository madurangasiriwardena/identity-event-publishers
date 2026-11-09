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

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.wso2.identity.event.delivery.core.security.BlockedAddressException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import javax.net.ssl.SSLHandshakeException;

/**
 * Tests for {@link ResponseClassifier}.
 */
public class ResponseClassifierTest {

    private final ResponseClassifier classifier = new ResponseClassifier();

    @DataProvider
    public Object[][] statusCodes() {

        return new Object[][]{
                {200, DeliveryOutcome.DELIVERED},
                {202, DeliveryOutcome.DELIVERED},
                {204, DeliveryOutcome.DELIVERED},
                {301, DeliveryOutcome.FAILED},
                {307, DeliveryOutcome.FAILED},
                {400, DeliveryOutcome.FAILED},
                {413, DeliveryOutcome.FAILED},
                {415, DeliveryOutcome.FAILED},
                {422, DeliveryOutcome.FAILED},
                {408, DeliveryOutcome.RETRY},
                {429, DeliveryOutcome.RETRY},
                {500, DeliveryOutcome.RETRY},
                {502, DeliveryOutcome.RETRY},
                {503, DeliveryOutcome.RETRY},
                {504, DeliveryOutcome.RETRY},
                {401, DeliveryOutcome.ENDPOINT_REJECTED},
                {403, DeliveryOutcome.ENDPOINT_REJECTED},
                {404, DeliveryOutcome.ENDPOINT_REJECTED},
                {410, DeliveryOutcome.ENDPOINT_REJECTED},
                {405, DeliveryOutcome.ENDPOINT_REJECTED},
                {501, DeliveryOutcome.ENDPOINT_REJECTED},
        };
    }

    @Test(dataProvider = "statusCodes")
    public void testClassifyStatusCode(int statusCode, DeliveryOutcome expected) {

        Assert.assertEquals(classifier.classify(statusCode), expected);
    }

    @DataProvider
    public Object[][] errors() {

        return new Object[][]{
                {new SocketTimeoutException("read timed out"), DeliveryOutcome.RETRY},
                {new ConnectException("refused"), DeliveryOutcome.RETRY},
                {new SSLHandshakeException("bad certificate"), DeliveryOutcome.RETRY},
                {new UnknownHostException("no such host"), DeliveryOutcome.RETRY},
                {new RuntimeException("wrapped", new IOException("reset")), DeliveryOutcome.RETRY},
                {new BlockedAddressException("internal.example"), DeliveryOutcome.FAILED},
                {new RuntimeException("wrapped", new IOException("io",
                        new BlockedAddressException("internal.example"))), DeliveryOutcome.FAILED},
                {new IllegalArgumentException("bad request"), DeliveryOutcome.FAILED},
        };
    }

    @Test(dataProvider = "errors")
    public void testClassifyError(Throwable error, DeliveryOutcome expected) {

        Assert.assertEquals(classifier.classify(error), expected);
    }

    @Test
    public void testCustomStatusSets() {

        ResponseClassifier custom = new ResponseClassifier(Collections.singleton(501),
                new HashSet<>(Arrays.asList(400, 409)));

        Assert.assertEquals(custom.classify(501), DeliveryOutcome.RETRY);
        Assert.assertEquals(custom.classify(409), DeliveryOutcome.FAILED);
        Assert.assertEquals(custom.classify(503), DeliveryOutcome.ENDPOINT_REJECTED);
        Assert.assertEquals(custom.getRetryableStatusCodes(), Collections.singleton(501));
        Assert.assertEquals(custom.getPermanentStatusCodes(), new HashSet<>(Arrays.asList(400, 409)));
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testRejectsCodeInBothSets() {

        new ResponseClassifier(Collections.singleton(500), Collections.singleton(500));
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testRejectsSuccessCode() {

        new ResponseClassifier(Collections.singleton(200), Collections.<Integer>emptySet());
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testRejectsRedirectCodeAsPermanent() {

        new ResponseClassifier(Collections.<Integer>emptySet(), Collections.singleton(302));
    }

    @Test
    public void testCauseChainLoopDoesNotHang() {

        LoopingException error = new LoopingException();
        Assert.assertEquals(classifier.classify(error), DeliveryOutcome.FAILED);
    }

    /**
     * An exception whose cause is itself, as some libraries produce.
     */
    private static final class LoopingException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        @Override
        public synchronized Throwable getCause() {

            return this;
        }
    }
}
