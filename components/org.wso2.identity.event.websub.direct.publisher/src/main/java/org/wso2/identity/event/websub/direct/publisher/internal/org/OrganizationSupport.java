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

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.core.ServiceURLBuilder;
import org.wso2.carbon.identity.core.URLBuilderException;
import org.wso2.carbon.identity.core.context.IdentityContext;
import org.wso2.carbon.identity.core.context.model.RootOrganization;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.organization.management.service.OrganizationManager;
import org.wso2.carbon.identity.organization.management.service.exception.OrganizationManagementException;
import org.wso2.carbon.identity.organization.management.service.util.OrganizationManagementUtil;
import org.wso2.identity.event.websub.direct.publisher.constant.WebSubDirectConstants.Topic;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * Organization lookups the adapter needs: organization IDs, topic names and the transmitter issuer.
 * <p>
 * The logic matches the WebSubHub and HTTP adapters, so topics and issuers are the same whichever adapter runs. All
 * Carbon static calls of the adapter live here, so the rest can be tested without a Carbon runtime.
 */
public class OrganizationSupport {

    private static final Log LOG = LogFactory.getLog(OrganizationSupport.class);

    private final Supplier<OrganizationManager> organizationManager;
    private final UrlBuilder urlBuilder;
    private final Supplier<String> contextRootTenantDomain;

    /**
     * Builds an absolute public URL from a path.
     */
    interface UrlBuilder {

        String absolutePublicUrl(String path) throws URLBuilderException;
    }

    /**
     * @param organizationManager Supplies the OSGi organization manager service at call time.
     */
    public OrganizationSupport(Supplier<OrganizationManager> organizationManager) {

        this(organizationManager, path -> ServiceURLBuilder.create().addPath(path).build().getAbsolutePublicURL(),
                OrganizationSupport::rootTenantDomainFromContext);
    }

    OrganizationSupport(Supplier<OrganizationManager> organizationManager, UrlBuilder urlBuilder,
                        Supplier<String> contextRootTenantDomain) {

        this.organizationManager = organizationManager;
        this.urlBuilder = urlBuilder;
        this.contextRootTenantDomain = contextRootTenantDomain;
    }

    /**
     * @param tenantDomain Tenant domain of the event.
     * @return True if events of this tenant go to the webhooks of the organizations that own them.
     */
    public boolean isOrganizationAwarePublishing(String tenantDomain) {

        if (!IdentityUtil.isChildOrganizationSubscriptionEnabled()) {
            return false;
        }
        try {
            return OrganizationManagementUtil.isOrganization(tenantDomain);
        } catch (OrganizationManagementException e) {
            LOG.warn("Could not check whether tenant " + tenantDomain + " is an organization: " + e.getMessage()
                    + ". Publishing the event to the tenant's own webhooks only.");
            return false;
        }
    }

    /**
     * @param tenantDomain Tenant domain.
     * @return Organization ID of the tenant.
     * @throws OrganizationManagementException If it cannot be resolved.
     */
    public String organizationId(String tenantDomain) throws OrganizationManagementException {

        return organizationManager.get().resolveOrganizationId(tenantDomain);
    }

    /**
     * @param tenantDomain Tenant domain.
     * @return Organization ID of the tenant's root organization.
     * @throws OrganizationManagementException If it cannot be resolved.
     */
    public String rootOrganizationId(String tenantDomain) throws OrganizationManagementException {

        String primary = organizationManager.get().getPrimaryOrganizationId(organizationId(tenantDomain));
        return StringUtils.isBlank(primary) ? organizationId(tenantDomain) : primary;
    }

    /**
     * Builds a WebSub topic name: {@code {tenantDomain}.{orgId}.schema.{profile}.{version}.event.{channel}}.
     *
     * @param channelUri          Channel URI, for example {@code https://schemas.identity.wso2.org/events/login}.
     * @param eventProfileName    Event profile name.
     * @param eventProfileVersion Event profile version.
     * @param tenantDomain        Tenant domain that owns the topic.
     * @return Topic name.
     * @throws OrganizationManagementException If the organization ID cannot be resolved.
     */
    public String topic(String channelUri, String eventProfileName, String eventProfileVersion, String tenantDomain)
            throws OrganizationManagementException {

        return tenantDomain + Topic.SEPARATOR + organizationId(tenantDomain) + Topic.SEPARATOR + Topic.SCHEMA
                + Topic.SEPARATOR + eventProfileName.toLowerCase(Locale.ROOT) + Topic.SEPARATOR
                + eventProfileVersion + Topic.SEPARATOR + Topic.EVENT + Topic.SEPARATOR + channelName(channelUri);
    }

    /**
     * Builds the issuer of the organization that owns a webhook: {@code /t/{root}} for a root organization,
     * {@code /t/{root}/o/{orgId}} for a sub-organization.
     *
     * @param ownerTenantDomain Tenant domain of the webhook owner.
     * @return Absolute issuer URL.
     * @throws OrganizationManagementException If the organization cannot be resolved.
     * @throws URLBuilderException             If the URL cannot be built.
     */
    public String transmitterIssuer(String ownerTenantDomain)
            throws OrganizationManagementException, URLBuilderException {

        String rootTenantDomain = rootTenantDomain(ownerTenantDomain);
        String path = StringUtils.equals(ownerTenantDomain, rootTenantDomain) ? "/t/" + rootTenantDomain
                : "/t/" + rootTenantDomain + "/o/" + organizationId(ownerTenantDomain);
        return urlBuilder.absolutePublicUrl(path);
    }

    static String channelName(String channelUri) {

        int lastSlash = channelUri.lastIndexOf('/');
        return lastSlash >= 0 && lastSlash < channelUri.length() - 1 ? channelUri.substring(lastSlash + 1) : "";
    }

    private String rootTenantDomain(String ownerTenantDomain) throws OrganizationManagementException {

        String fromContext = contextRootTenantDomain.get();
        if (StringUtils.isNotBlank(fromContext)) {
            return fromContext;
        }
        OrganizationManager manager = organizationManager.get();
        String primaryOrganizationId = manager.getPrimaryOrganizationId(manager.resolveOrganizationId(
                ownerTenantDomain));
        String rootTenantDomain = StringUtils.isBlank(primaryOrganizationId) ? null
                : manager.resolveTenantDomain(primaryOrganizationId);
        if (StringUtils.isBlank(rootTenantDomain)) {
            throw new OrganizationManagementException("Root organization of tenant: " + ownerTenantDomain
                    + " cannot be resolved.");
        }
        return rootTenantDomain;
    }

    private static String rootTenantDomainFromContext() {

        RootOrganization rootOrganization = IdentityContext.getThreadLocalIdentityContext().getRootOrganization();
        return rootOrganization == null ? null : rootOrganization.getAssociatedTenantDomain();
    }
}
