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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.wso2.carbon.identity.core.util.IdentityCoreInitializedEvent;
import org.wso2.carbon.identity.event.publisher.api.service.EventPublisher;
import org.wso2.carbon.identity.organization.management.service.OrganizationManager;
import org.wso2.carbon.identity.subscription.management.api.service.EventSubscriber;
import org.wso2.carbon.identity.topic.management.api.service.TopicManager;
import org.wso2.carbon.identity.webhook.management.api.service.WebhookManagementService;
import org.wso2.carbon.identity.webhook.metadata.api.exception.WebhookMetadataException;
import org.wso2.carbon.identity.webhook.metadata.api.service.EventAdapterMetadataService;
import org.wso2.identity.event.delivery.core.crypto.KeyRing;
import org.wso2.identity.event.delivery.core.crypto.PayloadCipher;
import org.wso2.identity.event.delivery.core.http.DeliveryHttpClient;
import org.wso2.identity.event.delivery.core.http.ResponseClassifier;
import org.wso2.identity.event.delivery.core.security.AddressPolicy;
import org.wso2.identity.event.delivery.core.security.EndpointValidator;
import org.wso2.identity.event.delivery.core.security.GuardedDnsResolver;
import org.wso2.identity.event.delivery.core.store.DeliveryStore;
import org.wso2.identity.event.websub.direct.publisher.config.WebSubDirectConfiguration;
import org.wso2.identity.event.websub.direct.publisher.constant.WebSubDirectConstants;
import org.wso2.identity.event.websub.direct.publisher.internal.dispatch.CircuitBreaker;
import org.wso2.identity.event.websub.direct.publisher.internal.dispatch.DeliveryObserver;
import org.wso2.identity.event.websub.direct.publisher.internal.dispatch.DispatchMetrics;
import org.wso2.identity.event.websub.direct.publisher.internal.dispatch.FailureStoreWriter;
import org.wso2.identity.event.websub.direct.publisher.internal.dispatch.WebhookDispatcher;
import org.wso2.identity.event.websub.direct.publisher.internal.org.OrganizationSupport;
import org.wso2.identity.event.websub.direct.publisher.internal.verify.SubscriptionVerifier;
import org.wso2.identity.event.websub.direct.publisher.service.WebSubDirectEventPublisher;
import org.wso2.identity.event.websub.direct.publisher.service.WebSubDirectEventSubscriber;
import org.wso2.identity.event.websub.direct.publisher.service.WebSubDirectTopicManager;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.naming.InitialContext;
import javax.sql.DataSource;

/**
 * OSGi component of the WebSub direct adapter. It reads {@code adapter.websubdirect.*}, builds the delivery
 * pipeline and registers the publisher, subscriber and topic manager. On shutdown it drains deliveries in flight to
 * the database (design section 5.1, rule 10).
 */
@Component(
        name = "org.wso2.identity.event.websub.direct.publisher.internal.component.WebSubDirectServiceComponent",
        immediate = true)
public class WebSubDirectServiceComponent {

    private static final Log LOG = LogFactory.getLog(WebSubDirectServiceComponent.class);
    private static final String ERROR_CODE_ADAPTER_NOT_FOUND = "WEBHOOKMETA-66011";

    private final List<ServiceRegistration<?>> registrations = new ArrayList<>();
    private DeliveryHttpClient client;
    private FailureStoreWriter writer;
    private WebhookDispatcher dispatcher;
    private ThreadPoolExecutor callbackExecutor;
    private WebSubDirectConfiguration configuration;

    @Activate
    protected void activate(ComponentContext context) {

        try {
            WebSubDirectDataHolder holder = WebSubDirectDataHolder.getInstance();
            configuration = new WebSubDirectConfiguration(holder.getEventAdapterMetadataService()
                    .getAdapterByName(WebSubDirectConstants.ADAPTER_NAME).getProperties());
            if (!configuration.isEnabled()) {
                LOG.debug("The websubdirect adapter is disabled.");
                return;
            }
            start(context);
            LOG.info("The websubdirect webhook adapter is active.");
        } catch (WebhookMetadataException e) {
            if (ERROR_CODE_ADAPTER_NOT_FOUND.equals(e.getErrorCode())) {
                LOG.debug("The websubdirect adapter is not configured.");
            } else {
                LOG.error("The websubdirect adapter cannot read its configuration.", e);
            }
        } catch (Exception e) {
            LOG.error("The websubdirect adapter cannot start. Webhook events will not be delivered.", e);
            stop();
        }
    }

    @Deactivate
    protected void deactivate(ComponentContext context) {

        stop();
        LOG.debug("The websubdirect adapter is stopped.");
    }

    private void start(ComponentContext context) throws Exception {

        DataSource dataSource = (DataSource) new InitialContext().lookup(configuration.getDataSourceName());
        KeyRing keyRing = KeyRing.fromDirectory(Paths.get(configuration.getKeysDirectory()),
                configuration.getActiveKeyId());
        DeliveryStore store = new DeliveryStore(dataSource, new PayloadCipher(keyRing));
        AddressPolicy addressPolicy = configuration.isAllowPrivateAddresses() ? AddressPolicy.allowAll()
                : AddressPolicy.publicOnly();
        if (addressPolicy.allowsNonPublicAddresses()) {
            LOG.warn("allowPrivateAddresses is on: webhooks can reach internal addresses. Use it only for local "
                    + "development.");
        }
        client = new DeliveryHttpClient(new DeliveryHttpClient.Settings(configuration.getConnectTimeoutMillis(),
                configuration.getReadTimeoutMillis(), configuration.getConnectionRequestTimeoutMillis(),
                configuration.getMaxConnections(), configuration.getMaxConnectionsPerRoute(),
                configuration.getIoThreadCount()), new GuardedDnsResolver(addressPolicy));
        EndpointValidator validator = new EndpointValidator(configuration.isAllowHttpEndpoints());
        DeliveryObserver observer = new DiagnosticDeliveryObserver();
        DispatchMetrics metrics = new DispatchMetrics();
        writer = new FailureStoreWriter(store, new FailureStoreWriter.Settings(configuration.getStoreThreads(),
                configuration.getStoreBufferMaxEntries(), configuration.getStoreBufferMaxAge(),
                configuration.getMaxPendingPerWebhook(), configuration.getRetryWindow()), observer, metrics);
        writer.start();
        // Result handling is cheap; when its queue is full it runs on the I/O thread rather than being dropped.
        callbackExecutor = new ThreadPoolExecutor(configuration.getCallbackThreads(),
                configuration.getCallbackThreads(), 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(configuration.getCallbackQueueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable, "websub-direct-callback");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.CallerRunsPolicy());
        dispatcher = new WebhookDispatcher(client, validator,
                new ResponseClassifier(configuration.getRetryableStatusCodes(),
                        configuration.getPermanentStatusCodes()),
                new CircuitBreaker(configuration.getCircuitFailureThreshold(),
                        configuration.getCircuitOpenDuration()),
                writer, observer, metrics, callbackExecutor,
                new WebhookDispatcher.Settings(configuration.getMaxInFlight(), configuration.getFirstRetryDelay()));
        OrganizationSupport organizations = new OrganizationSupport(
                () -> WebSubDirectDataHolder.getInstance().getOrganizationManager());
        SubscriptionVerifier verifier = new SubscriptionVerifier(client,
                configuration.getVerificationTimeoutMillis(), configuration.getLeaseSeconds());

        registrations.add(context.getBundleContext().registerService(EventPublisher.class,
                new WebSubDirectEventPublisher(
                        () -> WebSubDirectDataHolder.getInstance().getWebhookManagementService(), organizations,
                        dispatcher, configuration.getHubUrl()), null));
        registrations.add(context.getBundleContext().registerService(EventSubscriber.class,
                new WebSubDirectEventSubscriber(verifier, validator, organizations, store), null));
        registrations.add(context.getBundleContext().registerService(TopicManager.class,
                new WebSubDirectTopicManager(organizations), null));
    }

    private void stop() {

        for (ServiceRegistration<?> registration : registrations) {
            try {
                registration.unregister();
            } catch (IllegalStateException e) {
                // Already unregistered by the framework.
            }
        }
        registrations.clear();
        if (dispatcher != null) {
            dispatcher.shutdown(configuration.getShutdownGrace());
        }
        if (writer != null) {
            writer.shutdown(configuration.getShutdownGrace());
        }
        if (callbackExecutor != null) {
            callbackExecutor.shutdown();
        }
        if (client != null) {
            try {
                client.close();
            } catch (IOException e) {
                LOG.debug("Webhook HTTP client did not close cleanly.", e);
            }
        }
        dispatcher = null;
        writer = null;
        callbackExecutor = null;
        client = null;
    }

    @Reference(
            name = "identity.core.init.event.service",
            service = IdentityCoreInitializedEvent.class,
            cardinality = ReferenceCardinality.MANDATORY,
            policy = ReferencePolicy.DYNAMIC,
            unbind = "unsetIdentityCoreInitializedEventService")
    protected void setIdentityCoreInitializedEventService(IdentityCoreInitializedEvent event) {

        // Referenced so this component starts after identity core.
    }

    protected void unsetIdentityCoreInitializedEventService(IdentityCoreInitializedEvent event) {

        // Nothing to release.
    }

    @Reference(
            name = "identity.organization.management.component",
            service = OrganizationManager.class,
            cardinality = ReferenceCardinality.MANDATORY,
            policy = ReferencePolicy.DYNAMIC,
            unbind = "unsetOrganizationManager")
    protected void setOrganizationManager(OrganizationManager organizationManager) {

        WebSubDirectDataHolder.getInstance().setOrganizationManager(organizationManager);
    }

    protected void unsetOrganizationManager(OrganizationManager organizationManager) {

        WebSubDirectDataHolder.getInstance().setOrganizationManager(null);
    }

    @Reference(
            name = "identity.webhook.adapter.metadata.component",
            service = EventAdapterMetadataService.class,
            cardinality = ReferenceCardinality.MANDATORY,
            policy = ReferencePolicy.DYNAMIC,
            unbind = "unsetEventAdapterMetadataService")
    protected void setEventAdapterMetadataService(EventAdapterMetadataService service) {

        WebSubDirectDataHolder.getInstance().setEventAdapterMetadataService(service);
    }

    protected void unsetEventAdapterMetadataService(EventAdapterMetadataService service) {

        WebSubDirectDataHolder.getInstance().setEventAdapterMetadataService(null);
    }

    @Reference(
            name = "webhook.management.service.component",
            service = WebhookManagementService.class,
            cardinality = ReferenceCardinality.MANDATORY,
            policy = ReferencePolicy.DYNAMIC,
            unbind = "unsetWebhookManagementService")
    protected void setWebhookManagementService(WebhookManagementService service) {

        WebSubDirectDataHolder.getInstance().setWebhookManagementService(service);
    }

    protected void unsetWebhookManagementService(WebhookManagementService service) {

        WebSubDirectDataHolder.getInstance().setWebhookManagementService(null);
    }
}
