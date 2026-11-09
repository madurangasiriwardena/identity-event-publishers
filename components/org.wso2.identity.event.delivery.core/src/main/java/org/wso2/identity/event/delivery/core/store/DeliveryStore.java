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

package org.wso2.identity.event.delivery.core.store;

import org.wso2.identity.event.delivery.core.crypto.DeliveryCryptoException;
import org.wso2.identity.event.delivery.core.crypto.PayloadCipher;
import org.wso2.identity.event.delivery.core.signing.DeliveryHeaders;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

/**
 * The operations Identity Server performs on the delivery database. It connects with the {@code webhook_is} role,
 * which can store and cancel deliveries and record failures in the endpoint state, but cannot change a row's retry
 * state or reset an endpoint.
 */
public class DeliveryStore {

    static final String COLUMN_PAYLOAD = "payload";
    static final String COLUMN_HEADERS = "headers_enc";

    private static final String INSERT = "INSERT INTO webhook_delivery (id, event_id, tenant_id, root_tenant_id, "
            + "webhook_id, event_type, topic, endpoint_url, content_type, payload, headers_enc, key_id, status, "
            + "attempt_count, last_attempt_at, expires_at, last_status_code, last_error) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? THEN now() END, "
            + "now() + make_interval(secs => ?), ?, ?) "
            + "ON CONFLICT (event_id, webhook_id) DO NOTHING";

    // Counts the failed attempt and moves the webhook's next attempt to at least now + the first retry delay. A
    // later time that is already set, for example from the worker's backoff, is kept.
    private static final String ENDPOINT_FAILURE = "INSERT INTO webhook_endpoint_state (webhook_id, tenant_id, "
            + "consecutive_failures, next_attempt_at, last_failure_status_code, last_failure_at, updated_at) "
            + "VALUES (?, ?, 1, now() + make_interval(secs => ?), ?, now(), now()) "
            + "ON CONFLICT (webhook_id) DO UPDATE SET "
            + "consecutive_failures = webhook_endpoint_state.consecutive_failures + 1, "
            + "next_attempt_at = GREATEST(webhook_endpoint_state.next_attempt_at, EXCLUDED.next_attempt_at), "
            + "last_failure_status_code = EXCLUDED.last_failure_status_code, last_failure_at = now(), "
            + "updated_at = now()";

    private static final String COUNT_PENDING = "SELECT count(*) FROM webhook_delivery "
            + "WHERE webhook_id = ? AND status IN ('PENDING', 'IN_PROGRESS')";

    private static final String CANCEL_BY_WEBHOOK = "UPDATE webhook_delivery SET status = 'CANCELLED', "
            + "updated_at = now() WHERE webhook_id = ? AND status IN ('PENDING', 'IN_PROGRESS')";

    private static final String CANCEL_BY_WEBHOOK_AND_TOPIC = "UPDATE webhook_delivery SET status = 'CANCELLED', "
            + "updated_at = now() WHERE webhook_id = ? AND topic = ? AND status IN ('PENDING', 'IN_PROGRESS')";

    private static final String CANCEL_BY_ENDPOINT_AND_TOPIC = "UPDATE webhook_delivery SET status = 'CANCELLED', "
            + "updated_at = now() WHERE topic = ? AND endpoint_url = ? AND status IN ('PENDING', 'IN_PROGRESS')";

    private static final String DELETE_BY_TENANT = "DELETE FROM webhook_delivery WHERE tenant_id = ?";

    private static final String DELETE_BY_ROOT_TENANT = "DELETE FROM webhook_delivery WHERE root_tenant_id = ?";

    private final DataSource dataSource;
    private final PayloadCipher cipher;

    /**
     * @param dataSource Connections to the delivery database.
     * @param cipher     Cipher that encrypts payloads and headers before they are stored.
     */
    public DeliveryStore(DataSource dataSource, PayloadCipher cipher) {

        this.dataSource = dataSource;
        this.cipher = cipher;
    }

    /**
     * Stores a failed delivery. Storing the same event for the same webhook twice keeps the first row.
     * <p>
     * If IS tried the delivery and it will be retried (status PENDING), the failure is also counted in the webhook's
     * endpoint state, in the same transaction, and the webhook's next attempt moves to at least now + the first retry
     * delay. A delivery stored without a try, for example while the IS circuit breaker is open, and a final failure
     * of one event, such as a 400 response, leave the endpoint state unchanged. The worker treats final failures the
     * same way.
     *
     * @param delivery Delivery to store.
     * @return True if a row was added, false if the event was already stored for this webhook.
     * @throws DeliveryStoreException If encryption or the database write fails.
     */
    public boolean insert(NewDelivery delivery) throws DeliveryStoreException {

        UUID id = UUID.randomUUID();
        byte[] payload;
        byte[] headers;
        try {
            payload = cipher.encrypt(delivery.getPayload(), PayloadCipher.associatedData(id, COLUMN_PAYLOAD));
            headers = cipher.encrypt(DeliveryHeaders.encode(delivery.getHeaders()),
                    PayloadCipher.associatedData(id, COLUMN_HEADERS));
        } catch (DeliveryCryptoException e) {
            throw new DeliveryStoreException("Failed delivery cannot be encrypted.", e);
        }
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                boolean added = insertRow(connection, id, delivery, payload, headers);
                if (delivery.getAttemptCount() > 0 && delivery.getStatus() == DeliveryStatus.PENDING) {
                    recordEndpointFailure(connection, delivery);
                }
                connection.commit();
                return added;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new DeliveryStoreException("Failed delivery cannot be stored.", e);
        }
    }

    private boolean insertRow(Connection connection, UUID id, NewDelivery delivery, byte[] payload, byte[] headers)
            throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
            int i = 1;
            statement.setObject(i++, id);
            statement.setString(i++, delivery.getEventId());
            statement.setString(i++, delivery.getTenantId());
            statement.setString(i++, delivery.getRootTenantId());
            statement.setString(i++, delivery.getWebhookId());
            statement.setString(i++, delivery.getEventType());
            statement.setString(i++, delivery.getTopic());
            statement.setString(i++, delivery.getEndpointUrl());
            statement.setString(i++, DeliveryHeaders.APPLICATION_JSON);
            statement.setBytes(i++, payload);
            statement.setBytes(i++, headers);
            statement.setString(i++, cipher.getActiveKeyId());
            statement.setString(i++, delivery.getStatus().name());
            statement.setInt(i++, delivery.getAttemptCount());
            statement.setBoolean(i++, delivery.getAttemptCount() > 0);
            statement.setDouble(i++, SqlSupport.seconds(delivery.getRetryWindow()));
            SqlSupport.setNullableInt(statement, i++, delivery.getLastStatusCode());
            SqlSupport.setError(statement, i, delivery.getLastError());
            return statement.executeUpdate() == 1;
        }
    }

    private static void recordEndpointFailure(Connection connection, NewDelivery delivery) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement(ENDPOINT_FAILURE)) {
            statement.setString(1, delivery.getWebhookId());
            statement.setString(2, delivery.getTenantId());
            statement.setDouble(3, SqlSupport.seconds(delivery.getFirstRetryDelay()));
            SqlSupport.setNullableInt(statement, 4, delivery.getLastStatusCode());
            statement.executeUpdate();
        }
    }

    /**
     * Counts deliveries of a webhook that are still waiting or in progress. IS uses it to stop storing new failures
     * for one endpoint above a limit.
     *
     * @param webhookId Webhook ID.
     * @return Number of pending and in-progress rows.
     * @throws DeliveryStoreException If the query fails.
     */
    public long countPending(String webhookId) throws DeliveryStoreException {

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(COUNT_PENDING)) {
            statement.setString(1, webhookId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        } catch (SQLException e) {
            throw new DeliveryStoreException("Pending deliveries cannot be counted.", e);
        }
    }

    /**
     * Cancels every pending or in-progress delivery of a webhook, for example when it is deactivated. A worker that
     * is sending one of these rows finishes that attempt, but cannot move the row out of CANCELLED.
     *
     * @param webhookId Webhook ID.
     * @return Number of cancelled rows.
     * @throws DeliveryStoreException If the update fails.
     */
    public int cancelPending(String webhookId) throws DeliveryStoreException {

        return update(CANCEL_BY_WEBHOOK, "Pending deliveries cannot be cancelled.", webhookId);
    }

    /**
     * Cancels pending deliveries of one event channel of a webhook, for example when that channel is unsubscribed.
     *
     * @param webhookId Webhook ID.
     * @param topic     WebSub topic of the channel.
     * @return Number of cancelled rows.
     * @throws DeliveryStoreException If the update fails.
     */
    public int cancelPending(String webhookId, String topic) throws DeliveryStoreException {

        return update(CANCEL_BY_WEBHOOK_AND_TOPIC, "Pending deliveries cannot be cancelled.", webhookId, topic);
    }

    /**
     * Cancels pending deliveries of one channel, identified by the endpoint and topic. Identity Server uses it on
     * unsubscription, where the request carries the endpoint but not the webhook ID. Endpoints are unique within an
     * organization and the topic names the organization, so the pair identifies one webhook channel.
     *
     * @param endpointUrl Webhook endpoint URL.
     * @param topic       WebSub topic of the channel.
     * @return Number of cancelled rows.
     * @throws DeliveryStoreException If the update fails.
     */
    public int cancelPendingByEndpoint(String endpointUrl, String topic) throws DeliveryStoreException {

        return update(CANCEL_BY_ENDPOINT_AND_TOPIC, "Pending deliveries cannot be cancelled.", topic, endpointUrl);
    }

    /**
     * Deletes all rows of webhooks owned by a deleted organization. Rows owned by other organizations are kept,
     * even when their events describe the deleted organization.
     *
     * @param tenantId Organization that owned the webhooks.
     * @return Number of deleted rows.
     * @throws DeliveryStoreException If the delete fails.
     */
    public int deleteByTenant(String tenantId) throws DeliveryStoreException {

        return update(DELETE_BY_TENANT, "Deliveries of the organization cannot be deleted.", tenantId);
    }

    /**
     * Deletes all rows under a deleted root organization, including its sub-organizations.
     *
     * @param rootTenantId Root organization.
     * @return Number of deleted rows.
     * @throws DeliveryStoreException If the delete fails.
     */
    public int deleteByRootTenant(String rootTenantId) throws DeliveryStoreException {

        return update(DELETE_BY_ROOT_TENANT, "Deliveries of the root organization cannot be deleted.", rootTenantId);
    }

    private int update(String sql, String failureMessage, String... parameters) throws DeliveryStoreException {

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setString(i + 1, parameters[i]);
            }
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new DeliveryStoreException(failureMessage, e);
        }
    }
}
