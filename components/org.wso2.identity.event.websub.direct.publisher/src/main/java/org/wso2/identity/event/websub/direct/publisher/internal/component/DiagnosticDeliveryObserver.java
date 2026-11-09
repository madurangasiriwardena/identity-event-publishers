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
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.identity.central.log.mgt.utils.LoggerUtils;
import org.wso2.carbon.utils.DiagnosticLog;
import org.wso2.identity.event.delivery.core.store.DeliveryStatus;
import org.wso2.identity.event.websub.direct.publisher.constant.WebSubDirectConstants;
import org.wso2.identity.event.websub.direct.publisher.internal.dispatch.DeliveryObserver;
import org.wso2.identity.event.websub.direct.publisher.internal.dispatch.DeliveryRequest;

/**
 * Writes delivery results to the tenant's diagnostic logs, which organization admins can read in the Console.
 * Endpoint URLs, payloads and headers are never logged.
 */
public class DiagnosticDeliveryObserver implements DeliveryObserver {

    private static final Log LOG = LogFactory.getLog(DiagnosticDeliveryObserver.class);

    @Override
    public void onDelivered(DeliveryRequest request, int statusCode) {

        log(request, DiagnosticLog.ResultStatus.SUCCESS, "Event delivered to the webhook endpoint.");
    }

    @Override
    public void onAttemptFailed(DeliveryRequest request, String reason) {

        log(request, DiagnosticLog.ResultStatus.FAILED, "Event delivery to the webhook endpoint failed: " + reason);
    }

    @Override
    public void onStored(DeliveryRequest request, DeliveryStatus status) {

        log(request, status == DeliveryStatus.PENDING ? DiagnosticLog.ResultStatus.SUCCESS
                : DiagnosticLog.ResultStatus.FAILED, status == DeliveryStatus.PENDING
                ? "Event queued for retry." : "Event cannot be delivered and will not be retried.");
    }

    @Override
    public void onDropped(DeliveryRequest request, String reason) {

        log(request, DiagnosticLog.ResultStatus.FAILED, "Event could not be queued for retry: " + reason);
    }

    private void log(DeliveryRequest request, DiagnosticLog.ResultStatus status, String message) {

        try {
            PrivilegedCarbonContext.startTenantFlow();
            PrivilegedCarbonContext.getThreadLocalCarbonContext()
                    .setTenantDomain(request.getEventTenantDomain(), true);
            if (!LoggerUtils.isDiagnosticLogsEnabled()) {
                return;
            }
            DiagnosticLog.DiagnosticLogBuilder builder = new DiagnosticLog.DiagnosticLogBuilder(
                    WebSubDirectConstants.Log.COMPONENT, WebSubDirectConstants.Log.ACTION_PUBLISH);
            builder.inputParam(WebSubDirectConstants.Log.INPUT_EVENT_ID, request.getEventId())
                    .inputParam(WebSubDirectConstants.Log.INPUT_WEBHOOK_ID, request.getWebhookId())
                    .inputParam(WebSubDirectConstants.Log.INPUT_EVENT_TYPE, request.getEventType())
                    .inputParam(WebSubDirectConstants.Log.INPUT_TOPIC, request.getTopic())
                    .resultMessage(message)
                    .resultStatus(status)
                    .logDetailLevel(DiagnosticLog.LogDetailLevel.APPLICATION);
            LoggerUtils.triggerDiagnosticLogEvent(builder);
        } catch (RuntimeException e) {
            // Logging must never break delivery.
            LOG.debug("Diagnostic log for webhook delivery could not be written.", e);
        } finally {
            PrivilegedCarbonContext.endTenantFlow();
        }
    }
}
