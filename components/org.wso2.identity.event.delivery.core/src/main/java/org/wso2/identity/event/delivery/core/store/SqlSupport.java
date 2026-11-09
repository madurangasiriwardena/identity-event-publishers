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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Small JDBC helpers shared by the store classes.
 */
final class SqlSupport {

    static final int MAX_ERROR_LENGTH = 1024;

    private SqlSupport() {

    }

    /**
     * Converts a duration to seconds for {@code make_interval(secs => ?)}.
     */
    static double seconds(Duration duration) {

        return duration.toMillis() / 1000.0;
    }

    static void setNullableInt(PreparedStatement statement, int index, Integer value) throws SQLException {

        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    static void setError(PreparedStatement statement, int index, String error) throws SQLException {

        if (error == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH)
                    : error);
        }
    }

    static Instant instant(ResultSet resultSet, String column) throws SQLException {

        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
