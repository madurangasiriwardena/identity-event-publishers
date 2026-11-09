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

package org.wso2.identity.event.delivery.core.security;

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.net.URI;

/**
 * Tests for {@link EndpointValidator}.
 */
public class EndpointValidatorTest {

    @Test
    public void testAcceptsHttpsUrl() throws InvalidEndpointException {

        URI uri = new EndpointValidator(false).validate(" https://hooks.example.com:8443/asgardeo?x=1 ");

        Assert.assertEquals(uri.getHost(), "hooks.example.com");
        Assert.assertEquals(uri.getPort(), 8443);
    }

    @Test
    public void testAcceptsUpperCaseScheme() throws InvalidEndpointException {

        Assert.assertEquals(new EndpointValidator(false).validate("HTTPS://hooks.example.com/").getHost(),
                "hooks.example.com");
    }

    @Test
    public void testHttpAllowedOnlyWhenEnabled() throws InvalidEndpointException {

        Assert.assertEquals(new EndpointValidator(true).validate("http://localhost:8080/hook").getHost(),
                "localhost");
        Assert.assertThrows(InvalidEndpointException.class,
                () -> new EndpointValidator(false).validate("http://hooks.example.com/"));
    }

    @DataProvider
    public Object[][] invalidUrls() {

        return new Object[][]{
                {null},
                {""},
                {"   "},
                {"hooks.example.com/path"},
                {"ftp://hooks.example.com/"},
                {"https:///no-host"},
                {"https://user:pass@hooks.example.com/"},
                {"https://hooks.example.com/a b"},
        };
    }

    @Test(dataProvider = "invalidUrls", expectedExceptions = InvalidEndpointException.class)
    public void testRejectsInvalidUrl(String url) throws InvalidEndpointException {

        new EndpointValidator(true).validate(url);
    }

    @Test
    public void testMessageDoesNotContainUrl() {

        try {
            new EndpointValidator(false).validate("https://user:topsecret@hooks.example.com/");
            Assert.fail("URL with credentials must be rejected.");
        } catch (InvalidEndpointException e) {
            Assert.assertFalse(e.getMessage().contains("topsecret"));
        }
    }
}
