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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.organization.management.service.exception.OrganizationManagementException;
import org.wso2.carbon.identity.topic.management.api.exception.TopicManagementException;
import org.wso2.carbon.identity.topic.management.api.exception.TopicManagementServerException;
import org.wso2.carbon.identity.topic.management.api.service.TopicManager;
import org.wso2.identity.event.websub.direct.publisher.constant.WebSubDirectConstants;
import org.wso2.identity.event.websub.direct.publisher.internal.org.OrganizationSupport;

/**
 * Topic manager for the direct adapter. Topics have the same names as with the WebSubHub adapter, but they are only
 * names: there is no hub to register them in, so registration and deregistration do nothing.
 */
public class WebSubDirectTopicManager implements TopicManager {

    private static final Log LOG = LogFactory.getLog(WebSubDirectTopicManager.class);
    private static final String ERROR_CODE_TOPIC = "WSDIRECT-65002";

    private final OrganizationSupport organizations;

    public WebSubDirectTopicManager(OrganizationSupport organizations) {

        this.organizations = organizations;
    }

    @Override
    public String getAssociatedAdapter() {

        return WebSubDirectConstants.ADAPTER_NAME;
    }

    @Override
    public String constructTopic(String channelUri, String eventProfileName, String eventProfileVersion,
                                 String tenantDomain) throws TopicManagementException {

        try {
            return organizations.topic(channelUri, eventProfileName, eventProfileVersion, tenantDomain);
        } catch (OrganizationManagementException e) {
            throw new TopicManagementServerException(ERROR_CODE_TOPIC, "Error while constructing the topic.",
                    "The organization of tenant " + tenantDomain + " cannot be resolved.", e);
        }
    }

    @Override
    public void registerTopic(String topic, String tenantDomain) {

        LOG.debug("Topic " + topic + " needs no registration with the direct adapter.");
    }

    @Override
    public void deregisterTopic(String topic, String tenantDomain) {

        LOG.debug("Topic " + topic + " needs no deregistration with the direct adapter.");
    }
}
