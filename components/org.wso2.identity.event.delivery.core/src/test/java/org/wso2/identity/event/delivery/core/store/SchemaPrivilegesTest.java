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

import org.flywaydb.core.Flyway;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

/**
 * Checks that each application role can do what it needs and nothing more, and that the migrations are stable.
 */
public class SchemaPrivilegesTest {

    private static final String SEED = "INSERT INTO webhook_delivery (id, event_id, tenant_id, root_tenant_id,"
            + " webhook_id, event_type, topic, endpoint_url, content_type, payload, headers, status,"
            + " expires_at) VALUES (gen_random_uuid(), 'e1', 't', 'r', 'wh-1', 'type', 'topic',"
            + " 'https://x', 'application/json', '\\x00', '\\x00', 'PENDING', now())";

    @BeforeClass
    public void startDatabase() throws SQLException {

        PostgresTestSupport.start();
    }

    @BeforeMethod
    public void cleanTables() throws SQLException {

        PostgresTestSupport.truncate();
        PostgresTestSupport.execute(SEED);
    }

    @DataProvider
    public Object[][] deniedForIdentityServer() {

        return new Object[][]{
                {"UPDATE webhook_delivery SET attempt_count = 5"},
                {"UPDATE webhook_delivery SET last_attempt_at = now()"},
                {"UPDATE webhook_delivery SET locked_by = 'x'"},
                {"UPDATE webhook_endpoint_state SET last_success_at = now()"},
                {"DELETE FROM webhook_endpoint_state"},
                {"CREATE TABLE intruder (id INT)"},
                {"DROP TABLE webhook_delivery"},
        };
    }

    @Test(dataProvider = "deniedForIdentityServer")
    public void testIdentityServerRoleIsLimited(String sql) {

        assertDenied(PostgresTestSupport.isDataSource(), sql);
    }

    @DataProvider
    public Object[][] deniedForWorker() {

        return new Object[][]{
                {SEED.replace("'e1'", "'e2'")},
                {"CREATE TABLE intruder (id INT)"},
                {"TRUNCATE webhook_delivery"},
        };
    }

    @Test(dataProvider = "deniedForWorker")
    public void testWorkerRoleCannotCreateDeliveries(String sql) {

        assertDenied(PostgresTestSupport.workerDataSource(), sql);
    }

    @Test
    public void testIdentityServerRoleCanRecordEndpointFailures() throws SQLException {

        try (Connection connection = PostgresTestSupport.isDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            Assert.assertEquals(statement.executeUpdate("INSERT INTO webhook_endpoint_state (webhook_id, tenant_id,"
                    + " consecutive_failures, next_attempt_at) VALUES ('wh-1', 't', 1, now())"
                    + " ON CONFLICT (webhook_id) DO UPDATE SET consecutive_failures"
                    + " = webhook_endpoint_state.consecutive_failures + 1"), 1);
        }
    }

    @Test
    public void testIdentityServerRoleCanCancel() throws SQLException {

        try (Connection connection = PostgresTestSupport.isDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            Assert.assertEquals(statement.executeUpdate(
                    "UPDATE webhook_delivery SET status = 'CANCELLED', updated_at = now()"), 1);
        }
    }

    @Test
    public void testMigrationsAreValidAndRerunnable() throws SQLException {

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("isRole", PostgresTestSupport.IS_ROLE);
        placeholders.put("workerRole", PostgresTestSupport.WORKER_ROLE);
        Flyway flyway = Flyway.configure()
                .dataSource(PostgresTestSupport.adminDataSource())
                .locations("classpath:db/migration/webhook")
                .placeholders(placeholders)
                .load();

        flyway.validate();
        Assert.assertEquals(flyway.migrate().migrationsExecuted, 0, "A second run must apply nothing.");
        Assert.assertEquals(flyway.info().applied().length, 2);
    }

    private static void assertDenied(DataSource dataSource, String sql) {

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
            Assert.fail("Expected permission denied for: " + sql);
        } catch (SQLException e) {
            // 42501 is insufficient_privilege.
            Assert.assertEquals(e.getSQLState(), "42501", "Unexpected error for: " + sql + ": " + e.getMessage());
        }
    }
}
