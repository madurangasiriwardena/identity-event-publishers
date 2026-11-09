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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Locale;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.StreamHandler;

/**
 * Sends java.util.logging output to stdout, one line per record, which container log collectors on both clouds
 * pick up. Payloads, headers and secrets are never logged.
 */
final class LogSetup {

    /**
     * Logger of the worker and the delivery core. LOG_LEVEL applies only to these, so DEBUG never turns on
     * third-party debug output such as the HTTP client's wire log, which would print payloads and signatures.
     */
    static final String APPLICATION_LOGGER = "org.wso2.identity.event.delivery";

    private static final Logger APPLICATION = Logger.getLogger(APPLICATION_LOGGER);

    private LogSetup() {

    }

    /**
     * @param logLevel Value of the LOG_LEVEL variable: DEBUG, INFO (default), WARN or ERROR.
     */
    static void configure(String logLevel) {

        Level level = parseLevel(logLevel);
        Logger root = Logger.getLogger("");
        for (Handler handler : root.getHandlers()) {
            root.removeHandler(handler);
        }
        StdoutHandler handler = new StdoutHandler();
        handler.setLevel(Level.ALL);
        root.addHandler(handler);
        root.setLevel(Level.INFO);
        APPLICATION.setLevel(level);
        if (level == null) {
            APPLICATION.setLevel(Level.INFO);
            APPLICATION.warning("Unknown LOG_LEVEL '" + logLevel + "'. Use DEBUG, INFO, WARN or ERROR. Using INFO.");
        }
    }

    /**
     * @return The level, INFO for a missing value, or null for an unknown value.
     */
    static Level parseLevel(String logLevel) {

        if (logLevel == null || logLevel.trim().isEmpty()) {
            return Level.INFO;
        }
        switch (logLevel.trim().toUpperCase(Locale.ROOT)) {
            case "DEBUG":
                return Level.FINE;
            case "INFO":
                return Level.INFO;
            case "WARN":
            case "WARNING":
                return Level.WARNING;
            case "ERROR":
                return Level.SEVERE;
            default:
                return null;
        }
    }

    /**
     * Prints levels with the names used elsewhere in WSO2 logs: DEBUG, INFO, WARN, ERROR.
     */
    static String levelName(Level level) {

        if (level.intValue() >= Level.SEVERE.intValue()) {
            return "ERROR";
        }
        if (level.intValue() >= Level.WARNING.intValue()) {
            return "WARN";
        }
        if (level.intValue() >= Level.INFO.intValue()) {
            return "INFO";
        }
        return "DEBUG";
    }

    /**
     * Writes to stdout and flushes after each record, so lines reach the collector at once.
     */
    static final class StdoutHandler extends StreamHandler {

        StdoutHandler() {

            super(System.out, new LineFormatter());
        }

        @Override
        public synchronized void publish(LogRecord record) {

            super.publish(record);
            flush();
        }
    }

    /**
     * {@code <time> <level> <logger> - <message>}, with the stack trace on the following lines.
     */
    static final class LineFormatter extends Formatter {

        @Override
        public String format(LogRecord record) {

            StringBuilder line = new StringBuilder()
                    .append(Instant.ofEpochMilli(record.getMillis())).append(' ')
                    .append(levelName(record.getLevel())).append(' ')
                    .append(shortName(record.getLoggerName())).append(" - ")
                    .append(formatMessage(record)).append(System.lineSeparator());
            if (record.getThrown() != null) {
                StringWriter trace = new StringWriter();
                record.getThrown().printStackTrace(new PrintWriter(trace));
                line.append(trace);
            }
            return line.toString();
        }

        private static String shortName(String loggerName) {

            if (loggerName == null) {
                return "root";
            }
            int dot = loggerName.lastIndexOf('.');
            return dot < 0 ? loggerName : loggerName.substring(dot + 1);
        }
    }
}
