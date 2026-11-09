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

-- Group roles with the least privileges each component needs. They cannot log in. The deployment creates the login
-- users and grants them one of these roles, for example: GRANT webhook_is TO is_webhook_user;
-- Role names come from the Flyway placeholders isRole and workerRole, set by the deployment.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${isRole}') THEN
        CREATE ROLE ${isRole} NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${workerRole}') THEN
        CREATE ROLE ${workerRole} NOLOGIN;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA ${flyway:defaultSchema} TO ${isRole}, ${workerRole};

-- Identity Server: store failed deliveries, count pending rows per webhook, cancel rows of a deactivated webhook,
-- and delete rows of a deleted organization. It also records its own failed attempts in the endpoint state, which
-- sets the webhook's next attempt time. It cannot change a row's retry state, and it cannot reset an endpoint.
GRANT SELECT, INSERT, DELETE ON webhook_delivery TO ${isRole};
GRANT UPDATE (status, updated_at) ON webhook_delivery TO ${isRole};
GRANT SELECT, INSERT ON webhook_endpoint_state TO ${isRole};
GRANT UPDATE (consecutive_failures, next_attempt_at, last_failure_status_code, last_failure_at, updated_at)
    ON webhook_endpoint_state TO ${isRole};

-- Retry Worker: claim, send, record results, expire and purge. It cannot create deliveries.
GRANT SELECT, UPDATE, DELETE ON webhook_delivery TO ${workerRole};
GRANT SELECT, INSERT, UPDATE, DELETE ON webhook_endpoint_state TO ${workerRole};
