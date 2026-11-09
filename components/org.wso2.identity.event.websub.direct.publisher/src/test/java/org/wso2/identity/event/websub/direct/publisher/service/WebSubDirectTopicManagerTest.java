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

package org.wso2.identity.event.websub.direct.publisher.service;

import org.testng.Assert;
import org.testng.annotations.Test;
import org.wso2.carbon.identity.organization.management.service.exception.OrganizationManagementException;
import org.wso2.carbon.identity.topic.management.api.exception.TopicManagementException;
import org.wso2.identity.event.websub.direct.publisher.constant.WebSubDirectConstants;
import org.wso2.identity.event.websub.direct.publisher.internal.org.OrganizationSupport;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link WebSubDirectTopicManager}.
 */
public class WebSubDirectTopicManagerTest {

    private static final String CHANNEL = "https://schemas.identity.wso2.org/events/login";

    @Test
    public void testConstructTopicAndNoOpRegistration() throws Exception {

        OrganizationSupport organizations = mock(OrganizationSupport.class);
        when(organizations.topic(CHANNEL, "WSO2", "v1", "myorg")).thenReturn("myorg.org-1.schema.wso2.v1.event.login");
        WebSubDirectTopicManager manager = new WebSubDirectTopicManager(organizations);

        Assert.assertEquals(manager.constructTopic(CHANNEL, "WSO2", "v1", "myorg"),
                "myorg.org-1.schema.wso2.v1.event.login");
        manager.registerTopic("t", "myorg");
        manager.deregisterTopic("t", "myorg");
        Assert.assertEquals(manager.getAssociatedAdapter(), WebSubDirectConstants.ADAPTER_NAME);
    }

    @Test(expectedExceptions = TopicManagementException.class)
    public void testOrganizationFailureIsReported() throws Exception {

        OrganizationSupport organizations = mock(OrganizationSupport.class);
        when(organizations.topic(CHANNEL, "WSO2", "v1", "myorg"))
                .thenThrow(new OrganizationManagementException("not found"));

        new WebSubDirectTopicManager(organizations).constructTopic(CHANNEL, "WSO2", "v1", "myorg");
    }
}
