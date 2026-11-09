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

package org.wso2.identity.event.websub.direct.publisher.internal.component;

import org.wso2.carbon.identity.organization.management.service.OrganizationManager;
import org.wso2.carbon.identity.webhook.management.api.service.WebhookManagementService;
import org.wso2.carbon.identity.webhook.metadata.api.service.EventAdapterMetadataService;

/**
 * Holds the OSGi services the adapter uses. They are looked up at call time, because OSGi can replace them.
 */
public final class WebSubDirectDataHolder {

    private static final WebSubDirectDataHolder INSTANCE = new WebSubDirectDataHolder();

    private volatile WebhookManagementService webhookManagementService;
    private volatile EventAdapterMetadataService eventAdapterMetadataService;
    private volatile OrganizationManager organizationManager;

    private WebSubDirectDataHolder() {

    }

    public static WebSubDirectDataHolder getInstance() {

        return INSTANCE;
    }

    public WebhookManagementService getWebhookManagementService() {

        return webhookManagementService;
    }

    public void setWebhookManagementService(WebhookManagementService webhookManagementService) {

        this.webhookManagementService = webhookManagementService;
    }

    public EventAdapterMetadataService getEventAdapterMetadataService() {

        return eventAdapterMetadataService;
    }

    public void setEventAdapterMetadataService(EventAdapterMetadataService eventAdapterMetadataService) {

        this.eventAdapterMetadataService = eventAdapterMetadataService;
    }

    public OrganizationManager getOrganizationManager() {

        return organizationManager;
    }

    public void setOrganizationManager(OrganizationManager organizationManager) {

        this.organizationManager = organizationManager;
    }
}
