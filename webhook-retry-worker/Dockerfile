# Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
#
# WSO2 LLC. licenses this file to you under the Apache License,
# Version 2.0 (the "License"); you may not use this file except
# in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

# Webhook Retry Worker image. Build the JAR first:
#   mvn -pl webhook-retry-worker -am package
#   docker build -t webhook-retry-worker webhook-retry-worker
#
# Commands: "run" (default) retries deliveries; "migrate" applies the schema and exits.

FROM eclipse-temurin:21.0.5_11-jre-alpine

RUN addgroup -S -g 10001 worker && adduser -S -u 10001 -G worker worker

WORKDIR /app
COPY target/webhook-retry-worker.jar /app/webhook-retry-worker.jar

USER 10001
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/webhook-retry-worker.jar"]
CMD ["run"]
