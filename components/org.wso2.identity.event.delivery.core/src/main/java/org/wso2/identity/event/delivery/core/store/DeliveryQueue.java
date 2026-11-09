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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntFunction;

import javax.sql.DataSource;

/**
 * The operations the Retry Worker performs on the delivery database. It connects with the {@code webhook_worker}
 * role.
 * <p>
 * Every result update matches on the row ID, the worker that holds the lease, and status IN_PROGRESS. A replica whose
 * lease has expired, or whose row was cancelled, therefore changes nothing; the methods report that by returning
 * false or an empty value. All times use the database clock.
 */
public class DeliveryQueue {

    private static final String CLAIM = "WITH due AS ("
            + " SELECT d.id, COALESCE(s.consecutive_failures, 0) AS endpoint_failures"
            + " FROM webhook_delivery d"
            + " LEFT JOIN webhook_endpoint_state s ON s.webhook_id = d.webhook_id"
            + " WHERE d.status = 'PENDING'"
            + " AND (s.next_attempt_at IS NULL OR s.next_attempt_at <= now())"
            + " ORDER BY COALESCE(d.last_attempt_at, d.created_at)"
            + " LIMIT ?"
            + " FOR UPDATE OF d SKIP LOCKED),"
            + " claimed AS ("
            + " UPDATE webhook_delivery d SET status = 'IN_PROGRESS', locked_by = ?,"
            + " lock_expires_at = now() + make_interval(secs => ?), updated_at = now()"
            + " FROM due WHERE d.id = due.id"
            + " RETURNING d.id, d.event_id, d.tenant_id, d.webhook_id, d.event_type, d.endpoint_url,"
            + " d.attempt_count, d.last_attempt_at, d.created_at, d.expires_at, d.key_id, d.payload,"
            + " d.headers_enc, due.endpoint_failures)"
            // RETURNING has no defined order, so sort here. The row tried longest ago, or never tried, goes first.
            + " SELECT * FROM claimed ORDER BY COALESCE(last_attempt_at, created_at)";

    private static final String LEASE_MATCH = " WHERE id = ? AND locked_by = ? AND status = 'IN_PROGRESS'";

    private static final String DELETE_DELIVERED = "DELETE FROM webhook_delivery" + LEASE_MATCH;

    private static final String RESCHEDULE = "UPDATE webhook_delivery SET"
            + " attempt_count = attempt_count + 1, last_attempt_at = now(),"
            + " status = CASE WHEN now() + make_interval(secs => ?) > expires_at THEN 'EXPIRED' ELSE 'PENDING' END,"
            + " locked_by = NULL, lock_expires_at = NULL, last_status_code = ?, last_error = ?, updated_at = now()"
            + LEASE_MATCH + " RETURNING status";

    private static final String RELEASE = "UPDATE webhook_delivery SET status = 'PENDING', locked_by = NULL,"
            + " lock_expires_at = NULL, updated_at = now()" + LEASE_MATCH;

    private static final String MARK_FAILED = "UPDATE webhook_delivery SET status = 'FAILED',"
            + " attempt_count = attempt_count + ?, locked_by = NULL, lock_expires_at = NULL,"
            + " last_status_code = ?, last_error = ?, updated_at = now()" + LEASE_MATCH;

    private static final String RELEASE_EXPIRED_LEASES = "UPDATE webhook_delivery SET status = 'PENDING',"
            + " locked_by = NULL, lock_expires_at = NULL, updated_at = now()"
            + " WHERE status = 'IN_PROGRESS' AND lock_expires_at < now()";

    private static final String EXPIRE_OVERDUE = "UPDATE webhook_delivery SET status = 'EXPIRED', updated_at = now()"
            + " WHERE id IN (SELECT id FROM webhook_delivery WHERE status = 'PENDING' AND expires_at < now()"
            + " LIMIT ? FOR UPDATE SKIP LOCKED)";

    private static final String PURGE_TERMINAL = "DELETE FROM webhook_delivery WHERE id IN (SELECT id"
            + " FROM webhook_delivery WHERE status IN ('FAILED', 'EXPIRED', 'CANCELLED')"
            + " AND updated_at < now() - make_interval(secs => ?) LIMIT ?)";

    private static final String PURGE_ENDPOINT_STATES = "DELETE FROM webhook_endpoint_state s"
            + " WHERE s.updated_at < now() - make_interval(secs => ?)"
            + " AND NOT EXISTS (SELECT 1 FROM webhook_delivery d WHERE d.webhook_id = s.webhook_id"
            + " AND d.status IN ('PENDING', 'IN_PROGRESS'))";

    private static final String ENDPOINT_SUCCESS = "UPDATE webhook_endpoint_state SET consecutive_failures = 0,"
            + " next_attempt_at = NULL, last_success_at = now(), updated_at = now() WHERE webhook_id = ?";

    private static final String ENDPOINT_FAILURE = "INSERT INTO webhook_endpoint_state (webhook_id, tenant_id,"
            + " consecutive_failures, last_failure_status_code, last_failure_at, updated_at)"
            + " VALUES (?, ?, 1, ?, now(), now())"
            + " ON CONFLICT (webhook_id) DO UPDATE SET"
            + " consecutive_failures = webhook_endpoint_state.consecutive_failures + 1,"
            + " last_failure_status_code = EXCLUDED.last_failure_status_code, last_failure_at = now(),"
            + " updated_at = now()"
            + " RETURNING consecutive_failures";

    // Keeps a later time that is already set, for example from a concurrent failure in IS.
    private static final String ENDPOINT_SCHEDULE = "UPDATE webhook_endpoint_state"
            + " SET next_attempt_at = GREATEST(next_attempt_at, now() + make_interval(secs => ?))"
            + " WHERE webhook_id = ?";

    private static final String STATS = "SELECT"
            + " count(*) FILTER (WHERE d.status = 'PENDING') AS pending,"
            + " count(*) FILTER (WHERE d.status = 'PENDING'"
            + " AND (s.next_attempt_at IS NULL OR s.next_attempt_at <= now())) AS due,"
            + " count(*) FILTER (WHERE d.status = 'IN_PROGRESS') AS in_progress,"
            + " COALESCE(EXTRACT(EPOCH FROM now() - min(d.created_at) FILTER (WHERE d.status = 'PENDING')), 0)"
            + " AS oldest_seconds"
            + " FROM webhook_delivery d LEFT JOIN webhook_endpoint_state s ON s.webhook_id = d.webhook_id"
            + " WHERE d.status IN ('PENDING', 'IN_PROGRESS')";

    private final DataSource dataSource;
    private final PayloadCipher cipher;

    /**
     * @param dataSource Connections to the delivery database.
     * @param cipher     Cipher that decrypts stored payloads and headers.
     */
    public DeliveryQueue(DataSource dataSource, PayloadCipher cipher) {

        this.dataSource = dataSource;
        this.cipher = cipher;
    }

    /**
     * Claims pending rows of webhooks whose next attempt time has passed. Rows of other webhooks are skipped. Rows
     * locked by another replica's claim are skipped instead of waited for, so replicas never block each other.
     *
     * @param workerId  Unique ID of this replica, such as the pod name.
     * @param batchSize Maximum number of rows to claim.
     * @param lease     How long the claim is valid. It must cover sending the whole batch.
     * @return Claimed rows. The row tried longest ago, or never tried, comes first.
     * @throws DeliveryStoreException If the claim fails.
     */
    public List<ClaimedDelivery> claim(String workerId, int batchSize, Duration lease)
            throws DeliveryStoreException {

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(CLAIM)) {
            statement.setInt(1, batchSize);
            statement.setString(2, workerId);
            statement.setDouble(3, SqlSupport.seconds(lease));
            List<ClaimedDelivery> claimed = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    claimed.add(new ClaimedDelivery(
                            resultSet.getObject("id", UUID.class),
                            resultSet.getString("event_id"),
                            resultSet.getString("tenant_id"),
                            resultSet.getString("webhook_id"),
                            resultSet.getString("event_type"),
                            resultSet.getString("endpoint_url"),
                            resultSet.getInt("attempt_count"),
                            SqlSupport.instant(resultSet, "expires_at"),
                            resultSet.getInt("endpoint_failures"),
                            resultSet.getString("key_id"),
                            resultSet.getBytes("payload"),
                            resultSet.getBytes("headers_enc")));
                }
            }
            return claimed;
        } catch (SQLException e) {
            throw new DeliveryStoreException("Due deliveries cannot be claimed.", e);
        }
    }

    /**
     * Decrypts the request of a claimed row.
     *
     * @param delivery Claimed row.
     * @return Body bytes and headers to send.
     * @throws DeliveryCryptoException If the key is not in the key ring, or the stored data was changed.
     */
    public DeliveryContent open(ClaimedDelivery delivery) throws DeliveryCryptoException {

        byte[] payload = cipher.decrypt(delivery.getKeyId(), delivery.getEncryptedPayload(),
                PayloadCipher.associatedData(delivery.getId(), DeliveryStore.COLUMN_PAYLOAD));
        byte[] headers = cipher.decrypt(delivery.getKeyId(), delivery.getEncryptedHeaders(),
                PayloadCipher.associatedData(delivery.getId(), DeliveryStore.COLUMN_HEADERS));
        try {
            return new DeliveryContent(payload, DeliveryHeaders.decode(headers));
        } catch (IllegalArgumentException e) {
            throw new DeliveryCryptoException("Stored delivery headers cannot be decoded.", e);
        }
    }

    /**
     * Removes a delivered row.
     *
     * @param id       Row ID.
     * @param workerId ID of this replica.
     * @return False if this replica no longer holds the lease.
     * @throws DeliveryStoreException If the delete fails.
     */
    public boolean markDelivered(UUID id, String workerId) throws DeliveryStoreException {

        return updateLeased(DELETE_DELIVERED, "Delivered row cannot be removed.", id, workerId);
    }

    /**
     * Records a failed attempt of a row and returns it to PENDING. The row is sent again when its webhook's next
     * attempt time passes; see {@link #recordEndpointFailure}. If that time falls after the row's expiry time, the
     * row becomes EXPIRED instead.
     *
     * @param id         Row ID.
     * @param workerId   ID of this replica.
     * @param delay      Time until the webhook's next attempt, used only for the expiry check.
     * @param statusCode HTTP status of the failed attempt, or null if there was no response.
     * @param error      Short reason, without secrets or payload data.
     * @return The new status, PENDING or EXPIRED. Empty if this replica no longer holds the lease.
     * @throws DeliveryStoreException If the update fails.
     */
    public Optional<DeliveryStatus> reschedule(UUID id, String workerId, Duration delay, Integer statusCode,
                                               String error) throws DeliveryStoreException {

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(RESCHEDULE)) {
            statement.setDouble(1, SqlSupport.seconds(delay));
            SqlSupport.setNullableInt(statement, 2, statusCode);
            SqlSupport.setError(statement, 3, error);
            statement.setObject(4, id);
            statement.setString(5, workerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(DeliveryStatus.valueOf(resultSet.getString(1)))
                        : Optional.<DeliveryStatus>empty();
            }
        } catch (SQLException e) {
            throw new DeliveryStoreException("Failed attempt cannot be recorded.", e);
        }
    }

    /**
     * Returns a claimed row to PENDING without counting an attempt, for example when another row of the same
     * webhook just failed and the webhook's next attempt moved into the future. The row keeps its last attempt time,
     * so it is ahead of the row that just failed in the next claim.
     *
     * @param id       Row ID.
     * @param workerId ID of this replica.
     * @return False if this replica no longer holds the lease.
     * @throws DeliveryStoreException If the update fails.
     */
    public boolean release(UUID id, String workerId) throws DeliveryStoreException {

        return updateLeased(RELEASE, "Claimed row cannot be released.", id, workerId);
    }

    /**
     * Fails a row for good.
     *
     * @param id            Row ID.
     * @param workerId      ID of this replica.
     * @param attemptMade   True if a request was sent, false if the row failed before sending (for example it
     *                      could not be decrypted).
     * @param statusCode    HTTP status, or null if there was no response.
     * @param error         Short reason, without secrets or payload data.
     * @return False if this replica no longer holds the lease.
     * @throws DeliveryStoreException If the update fails.
     */
    public boolean markFailed(UUID id, String workerId, boolean attemptMade, Integer statusCode, String error)
            throws DeliveryStoreException {

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(MARK_FAILED)) {
            statement.setInt(1, attemptMade ? 1 : 0);
            SqlSupport.setNullableInt(statement, 2, statusCode);
            SqlSupport.setError(statement, 3, error);
            statement.setObject(4, id);
            statement.setString(5, workerId);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new DeliveryStoreException("Failed row cannot be recorded.", e);
        }
    }

    /**
     * Returns rows whose lease has expired to PENDING. A lease expires when a replica crashed or hung while it held
     * the rows. No attempt is counted, because it is unknown whether a request was sent.
     *
     * @return Number of released rows.
     * @throws DeliveryStoreException If the update fails.
     */
    public int releaseExpiredLeases() throws DeliveryStoreException {

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(RELEASE_EXPIRED_LEASES)) {
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new DeliveryStoreException("Expired leases cannot be released.", e);
        }
    }

    /**
     * Expires pending rows whose retry window has passed. The claim query never picks rows of a webhook whose next
     * attempt is in the future, so this sweep is what ends them.
     *
     * @param limit Maximum number of rows to expire in one call.
     * @return Number of expired rows.
     * @throws DeliveryStoreException If the update fails.
     */
    public int expireOverdue(int limit) throws DeliveryStoreException {

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(EXPIRE_OVERDUE)) {
            statement.setInt(1, limit);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new DeliveryStoreException("Overdue rows cannot be expired.", e);
        }
    }

    /**
     * Deletes FAILED, EXPIRED and CANCELLED rows older than the retention period.
     *
     * @param retention How long rows in a final state are kept.
     * @param limit     Maximum number of rows to delete in one call.
     * @return Number of deleted rows.
     * @throws DeliveryStoreException If the delete fails.
     */
    public int purgeTerminal(Duration retention, int limit) throws DeliveryStoreException {

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(PURGE_TERMINAL)) {
            statement.setDouble(1, SqlSupport.seconds(retention));
            statement.setInt(2, limit);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new DeliveryStoreException("Old rows cannot be purged.", e);
        }
    }

    /**
     * Deletes endpoint state of webhooks that have no pending work and no recent change.
     *
     * @param retention How long an unchanged endpoint state is kept.
     * @return Number of deleted states.
     * @throws DeliveryStoreException If the delete fails.
     */
    public int purgeEndpointStates(Duration retention) throws DeliveryStoreException {

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(PURGE_ENDPOINT_STATES)) {
            statement.setDouble(1, SqlSupport.seconds(retention));
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new DeliveryStoreException("Old endpoint states cannot be purged.", e);
        }
    }

    /**
     * Resets an endpoint after a successful delivery. Its rows can be sent at once.
     *
     * @param webhookId Webhook ID.
     * @throws DeliveryStoreException If the update fails.
     */
    public void recordEndpointSuccess(String webhookId) throws DeliveryStoreException {

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(ENDPOINT_SUCCESS)) {
            statement.setString(1, webhookId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new DeliveryStoreException("Endpoint success cannot be recorded.", e);
        }
    }

    /**
     * Counts a failure of an endpoint and sets the webhook's next attempt time. The wait is computed from the new
     * failure count, in the same transaction, so concurrent failures from several replicas and IS each count once.
     * A later next attempt time that is already set is kept.
     *
     * @param webhookId  Webhook ID.
     * @param tenantId   Organization that owns the webhook.
     * @param statusCode HTTP status, or null if there was no response.
     * @param pauseFor   Returns the wait before the next attempt for a given consecutive failure count.
     * @return The new consecutive failure count.
     * @throws DeliveryStoreException If the update fails.
     */
    public int recordEndpointFailure(String webhookId, String tenantId, Integer statusCode,
                                     IntFunction<Duration> pauseFor) throws DeliveryStoreException {

        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                int failures;
                try (PreparedStatement statement = connection.prepareStatement(ENDPOINT_FAILURE)) {
                    statement.setString(1, webhookId);
                    statement.setString(2, tenantId);
                    SqlSupport.setNullableInt(statement, 3, statusCode);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        resultSet.next();
                        failures = resultSet.getInt(1);
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(ENDPOINT_SCHEDULE)) {
                    statement.setDouble(1, SqlSupport.seconds(pauseFor.apply(failures)));
                    statement.setString(2, webhookId);
                    statement.executeUpdate();
                }
                connection.commit();
                return failures;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new DeliveryStoreException("Endpoint failure cannot be recorded.", e);
        }
    }

    /**
     * @return Size and age of the queue.
     * @throws DeliveryStoreException If the query fails.
     */
    public QueueStats stats() throws DeliveryStoreException {

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(STATS);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return new QueueStats(resultSet.getLong("pending"), resultSet.getLong("due"),
                    resultSet.getLong("in_progress"),
                    Duration.ofMillis(Math.round(resultSet.getDouble("oldest_seconds") * 1000)));
        } catch (SQLException e) {
            throw new DeliveryStoreException("Queue statistics cannot be read.", e);
        }
    }

    private boolean updateLeased(String sql, String failureMessage, UUID id, String workerId)
            throws DeliveryStoreException {

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            statement.setString(2, workerId);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new DeliveryStoreException(failureMessage, e);
        }
    }
}
