-- Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
--
-- WSO2 LLC. licenses this file to you under the Apache License,
-- Version 2.0 (the "License"); you may not use this file except
-- in compliance with the License.
-- You may obtain a copy of the License at
--
-- http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing,
-- software distributed under the License is distributed on an
-- "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
-- KIND, either express or implied.  See the License for the
-- specific language governing permissions and limitations
-- under the License.

-- Webhook deliveries that failed their first attempt, and their retry state.
-- Successful deliveries are deleted at once, so this table holds only work in progress and recent final failures.
-- The time of the next attempt is kept per webhook, in webhook_endpoint_state.next_attempt_at. This table only records
-- when each row was last tried, which sets the order in which a webhook's rows are sent.
CREATE TABLE webhook_delivery (
    id                UUID          PRIMARY KEY,
    event_id          VARCHAR(64)   NOT NULL,   -- SET "jti", also sent as x-hub-messageId
    tenant_id         VARCHAR(64)   NOT NULL,   -- organization that owns the webhook (root or sub-organization)
    root_tenant_id    VARCHAR(64)   NOT NULL,   -- root organization of the owner
    webhook_id        VARCHAR(64)   NOT NULL,
    event_type        VARCHAR(255)  NOT NULL,   -- event URI
    topic             VARCHAR(512)  NOT NULL,   -- WebSub topic of the event channel
    endpoint_url      VARCHAR(2048) NOT NULL,
    content_type      VARCHAR(100)  NOT NULL,
    payload           BYTEA         NOT NULL,   -- AES-256-GCM; decrypts to the exact bytes that were signed
    headers_enc       BYTEA         NOT NULL,   -- AES-256-GCM; decrypts to the encoded delivery headers
    key_id            VARCHAR(64)   NOT NULL,   -- encryption key of payload and headers_enc
    status            VARCHAR(16)   NOT NULL,
    attempt_count     INTEGER       NOT NULL DEFAULT 0,
    last_attempt_at   TIMESTAMPTZ,              -- NULL if never tried; IS may store an event without trying it
    expires_at        TIMESTAMPTZ   NOT NULL,
    locked_by         VARCHAR(128),
    lock_expires_at   TIMESTAMPTZ,
    last_status_code  INTEGER,
    last_error        VARCHAR(1024),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_webhook_delivery_event_webhook UNIQUE (event_id, webhook_id),
    CONSTRAINT ck_webhook_delivery_status
        CHECK (status IN ('PENDING', 'IN_PROGRESS', 'FAILED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT ck_webhook_delivery_lease
        CHECK (status <> 'IN_PROGRESS' OR (locked_by IS NOT NULL AND lock_expires_at IS NOT NULL)),
    CONSTRAINT ck_webhook_delivery_attempts CHECK (attempt_count >= 0)
);

-- Pending rows in send order: the claim query. The row tried longest ago, or never tried, goes first.
CREATE INDEX idx_webhook_delivery_pending ON webhook_delivery ((COALESCE(last_attempt_at, created_at)))
    WHERE status = 'PENDING';
-- Expired leases: the lease reaper.
CREATE INDEX idx_webhook_delivery_lease ON webhook_delivery (lock_expires_at) WHERE status = 'IN_PROGRESS';
-- Cancel by webhook, pending count per webhook.
CREATE INDEX idx_webhook_delivery_webhook ON webhook_delivery (webhook_id, status);
-- Cancel by channel on unsubscription, where IS knows the endpoint and topic but not the webhook ID.
CREATE INDEX idx_webhook_delivery_topic ON webhook_delivery (topic, status);
-- Cleanup when an organization is deleted.
CREATE INDEX idx_webhook_delivery_tenant ON webhook_delivery (tenant_id);
CREATE INDEX idx_webhook_delivery_root_tenant ON webhook_delivery (root_tenant_id);
-- Purge of rows in a final state.
CREATE INDEX idx_webhook_delivery_terminal ON webhook_delivery (updated_at)
    WHERE status IN ('FAILED', 'EXPIRED', 'CANCELLED');

-- Retry schedule and health of each webhook endpoint. A row exists only while the endpoint has recent failures.
-- Failures from IS and from the Retry Worker both count. next_attempt_at is the one place that says when the webhook
-- is tried next: while it is in the future, the claim query skips every row of that webhook.
-- A success resets consecutive_failures and next_attempt_at but keeps the last failure's status and time, so the
-- row shows how the endpoint last failed and when it recovered.
CREATE TABLE webhook_endpoint_state (
    webhook_id            VARCHAR(64)  PRIMARY KEY,
    tenant_id             VARCHAR(64)  NOT NULL,
    consecutive_failures  INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at       TIMESTAMPTZ,       -- NULL or past: the webhook's rows can be sent now
    last_failure_status_code INTEGER,     -- HTTP status of the last failure; NULL if it had no response
    last_failure_at       TIMESTAMPTZ,
    last_success_at       TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_webhook_endpoint_state_failures CHECK (consecutive_failures >= 0)
);
