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

package org.wso2.identity.event.websub.direct.publisher.internal.org;

import org.mockito.MockedStatic;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.organization.management.service.OrganizationManager;
import org.wso2.carbon.identity.organization.management.service.exception.OrganizationManagementException;
import org.wso2.carbon.identity.organization.management.service.util.OrganizationManagementUtil;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link OrganizationSupport}.
 */
public class OrganizationSupportTest {

    private OrganizationManager manager;
    private OrganizationSupport support;

    @BeforeMethod
    public void setUp() throws Exception {

        manager = mock(OrganizationManager.class);
        when(manager.resolveOrganizationId("myorg")).thenReturn("org-1");
        when(manager.resolveOrganizationId("sub")).thenReturn("org-2");
        when(manager.getPrimaryOrganizationId("org-2")).thenReturn("org-1");
        // A root organization is its own primary organization.
        when(manager.getPrimaryOrganizationId("org-1")).thenReturn("org-1");
        when(manager.resolveTenantDomain("org-1")).thenReturn("myorg");
        support = new OrganizationSupport(() -> manager, path -> "https://api.example.com" + path, () -> null);
    }

    @Test
    public void testTopicMatchesWebSubHubFormat() throws Exception {

        Assert.assertEquals(support.topic("https://schemas.identity.wso2.org/events/login", "WSO2", "v1", "myorg"),
                "myorg.org-1.schema.wso2.v1.event.login");
    }

    @Test
    public void testChannelName() {

        Assert.assertEquals(OrganizationSupport.channelName("https://schemas.identity.wso2.org/events/session"),
                "session");
        Assert.assertEquals(OrganizationSupport.channelName("https://x/"), "");
        // Same as the WebSubHub adapter: a channel URI without a slash has no channel name.
        Assert.assertEquals(OrganizationSupport.channelName("plain"), "");
    }

    @Test
    public void testRootOrganizationId() throws Exception {

        Assert.assertEquals(support.rootOrganizationId("sub"), "org-1");
        Assert.assertEquals(support.rootOrganizationId("myorg"), "org-1");
        when(manager.resolveOrganizationId("legacy")).thenReturn("org-7");
        Assert.assertEquals(support.rootOrganizationId("legacy"), "org-7",
                "Without a primary organization, the organization is its own root.");
    }

    @Test
    public void testOrganizationAwarePublishing() throws Exception {

        try (MockedStatic<IdentityUtil> identityUtil = mockStatic(IdentityUtil.class);
             MockedStatic<OrganizationManagementUtil> orgUtil = mockStatic(OrganizationManagementUtil.class)) {
            identityUtil.when(IdentityUtil::isChildOrganizationSubscriptionEnabled).thenReturn(false);
            Assert.assertFalse(support.isOrganizationAwarePublishing("sub"));

            identityUtil.when(IdentityUtil::isChildOrganizationSubscriptionEnabled).thenReturn(true);
            orgUtil.when(() -> OrganizationManagementUtil.isOrganization("sub")).thenReturn(true);
            orgUtil.when(() -> OrganizationManagementUtil.isOrganization("myorg")).thenReturn(false);
            orgUtil.when(() -> OrganizationManagementUtil.isOrganization("broken"))
                    .thenThrow(new OrganizationManagementException("lookup failed"));
            Assert.assertTrue(support.isOrganizationAwarePublishing("sub"));
            Assert.assertFalse(support.isOrganizationAwarePublishing("myorg"));
            Assert.assertFalse(support.isOrganizationAwarePublishing("broken"));
        }
    }

    @Test
    public void testTransmitterIssuer() throws Exception {

        Assert.assertEquals(support.transmitterIssuer("myorg"), "https://api.example.com/t/myorg");
        Assert.assertEquals(support.transmitterIssuer("sub"), "https://api.example.com/t/myorg/o/org-2");
    }

    @Test
    public void testRootFromRequestContextIsPreferred() throws Exception {

        OrganizationSupport withContext = new OrganizationSupport(() -> manager,
                path -> "https://api.example.com" + path, () -> "myorg");

        Assert.assertEquals(withContext.transmitterIssuer("sub"), "https://api.example.com/t/myorg/o/org-2");
    }

    @Test(expectedExceptions = OrganizationManagementException.class)
    public void testUnresolvableRootFails() throws Exception {

        when(manager.resolveOrganizationId("orphan")).thenReturn("org-9");

        support.transmitterIssuer("orphan");
    }
}
