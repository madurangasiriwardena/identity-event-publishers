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

package org.wso2.identity.event.delivery.worker;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Tests for {@link LogSetup}.
 */
public class LogSetupTest {

    @AfterMethod
    public void resetLevels() {

        Logger.getLogger(LogSetup.APPLICATION_LOGGER).setLevel(null);
        Logger.getLogger("").setLevel(Level.INFO);
    }

    @DataProvider
    public Object[][] levels() {

        return new Object[][]{
                {null, Level.INFO},
                {"", Level.INFO},
                {"DEBUG", Level.FINE},
                {" debug ", Level.FINE},
                {"INFO", Level.INFO},
                {"WARN", Level.WARNING},
                {"warning", Level.WARNING},
                {"ERROR", Level.SEVERE},
                {"TRACE", null},
        };
    }

    @Test(dataProvider = "levels")
    public void testParseLevel(String value, Level expected) {

        Assert.assertEquals(LogSetup.parseLevel(value), expected);
    }

    @Test
    public void testLevelNames() {

        Assert.assertEquals(LogSetup.levelName(Level.FINE), "DEBUG");
        Assert.assertEquals(LogSetup.levelName(Level.FINEST), "DEBUG");
        Assert.assertEquals(LogSetup.levelName(Level.INFO), "INFO");
        Assert.assertEquals(LogSetup.levelName(Level.WARNING), "WARN");
        Assert.assertEquals(LogSetup.levelName(Level.SEVERE), "ERROR");
    }

    @Test
    public void testDebugAppliesOnlyToTheWorker() {

        LogSetup.configure("DEBUG");

        Assert.assertTrue(Logger.getLogger("org.wso2.identity.event.delivery.worker.RetryWorker")
                .isLoggable(Level.FINE));
        Assert.assertTrue(Logger.getLogger("org.wso2.identity.event.delivery.core.store.DeliveryQueue")
                .isLoggable(Level.FINE));
        Assert.assertFalse(Logger.getLogger("org.apache.http.wire").isLoggable(Level.FINE),
                "The HTTP wire log would print payloads and signatures.");
        Assert.assertFalse(Logger.getLogger("com.zaxxer.hikari").isLoggable(Level.FINE));
    }

    @Test
    public void testUnknownLevelFallsBackToInfo() {

        LogSetup.configure("TRACE");

        Assert.assertTrue(Logger.getLogger(LogSetup.APPLICATION_LOGGER).isLoggable(Level.INFO));
        Assert.assertFalse(Logger.getLogger(LogSetup.APPLICATION_LOGGER).isLoggable(Level.FINE));
    }

    @Test
    public void testLineFormat() {

        LogRecord record = new LogRecord(Level.FINE, "Claimed 3 due deliveries for 2 webhooks.");
        record.setLoggerName("org.wso2.identity.event.delivery.worker.RetryWorker");
        LogRecord failure = new LogRecord(Level.WARNING, "Claim cycle failed.");
        failure.setLoggerName("org.wso2.identity.event.delivery.worker.RetryWorker");
        failure.setThrown(new IllegalStateException("boom"));

        String debug = new LogSetup.LineFormatter().format(record);
        String warn = new LogSetup.LineFormatter().format(failure);

        Assert.assertTrue(debug.contains(" DEBUG RetryWorker - Claimed 3 due deliveries"), debug);
        Assert.assertTrue(warn.contains(" WARN RetryWorker - Claim cycle failed."), warn);
        Assert.assertTrue(warn.contains("IllegalStateException: boom"), warn);
    }
}
