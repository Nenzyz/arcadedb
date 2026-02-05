/*
 * Copyright © 2021-present Arcade Data Ltd (info@arcadedata.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-FileCopyrightText: 2021-present Arcade Data Ltd (info@arcadedata.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.arcadedb.bolt;

import com.arcadedb.GlobalConfiguration;
import com.arcadedb.test.BaseGraphServerTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.*;
import org.neo4j.driver.Record;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Integration tests for BOLT protocol session.run() behavior.
 */
public class BoltSessionRunIT extends BaseGraphServerTest {

  private static final int TEST_BOLT_PORT = 17689;

  @Override
  public void setTestConfiguration() {
    super.setTestConfiguration();
    GlobalConfiguration.SERVER_PLUGINS.setValue("Bolt:com.arcadedb.bolt.BoltProtocolPlugin");
    GlobalConfiguration.BOLT_PORT.setValue(TEST_BOLT_PORT);
  }

  @AfterEach
  @Override
  public void endTest() {
    GlobalConfiguration.SERVER_PLUGINS.setValue("");
    GlobalConfiguration.BOLT_PORT.reset();
    super.endTest();
  }

  private Driver getDriver() {
    return GraphDatabase.driver(
        "bolt://127.0.0.1:" + TEST_BOLT_PORT,
        AuthTokens.basic("root", DEFAULT_PASSWORD_FOR_TESTS),
        Config.builder()
            .withoutEncryption()
            .build()
    );
  }

  @Test
  void testAutoCommitQueryWithNoParameters() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        final var result = session.run("RETURN 42 AS value");
        assertThat(result.single().get("value").asInt()).isEqualTo(42);
      }
    }
  }

  @Test
  void testAutoCommitQueryWithParameters() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        final var result = session.run("RETURN $name AS name, $age AS age",
            Map.of("name", "Alice", "age", 30));
        final var record = result.single();
        assertThat(record.get("name").asString()).isEqualTo("Alice");
        assertThat(record.get("age").asInt()).isEqualTo(30);
      }
    }
  }

  @Test
  void testResultConsumptionIterateAllRecords() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        final var result = session.run("UNWIND [1, 2, 3] AS x RETURN x");
        final List<Record> records = result.list();
        assertThat(records).hasSize(3);
        assertThat(records.get(0).get("x").asInt()).isEqualTo(1);
        assertThat(records.get(1).get("x").asInt()).isEqualTo(2);
        assertThat(records.get(2).get("x").asInt()).isEqualTo(3);
      }
    }
  }

  @Test
  void testResultConsumptionConsumeSummaryOnly() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        // Use a write query and consume summary without iterating records
        final var result = session.run("CREATE (n:SummaryTest {val: 1}) RETURN n");
        final var summary = result.consume();
        assertThat(summary).isNotNull();
        // Verify summary object is returned with the original query text
        assertThat(summary.query().text()).contains("CREATE");

        // Cleanup
        session.run("MATCH (n:SummaryTest) DELETE n").consume();
      }
    }
  }

  @Test
  void testQueryAfterErrorRecovery() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        // Cause an error
        try {
          session.run("INVALID CYPHER").consume();
          fail("Expected exception");
        } catch (final Exception e) {
          // Expected
        }

        // Driver sends RESET automatically; verify session is usable
        final var result = session.run("RETURN 'recovered' AS status");
        assertThat(result.single().get("status").asString()).isEqualTo("recovered");
      }
    }
  }

  @Test
  void testDatabaseSelectionViaSessionConfig() {
    try (Driver driver = getDriver()) {
      // Use the explicit test database name via SessionConfig
      final SessionConfig config = SessionConfig.builder()
          .withDatabase(getDatabaseName())
          .build();
      try (Session session = driver.session(config)) {
        final var result = session.run("RETURN 1 AS n");
        assertThat(result.single().get("n").asInt()).isEqualTo(1);
      }

      // Verify default session (no database specified) also works
      try (Session session = driver.session()) {
        final var result = session.run("RETURN 2 AS n");
        assertThat(result.single().get("n").asInt()).isEqualTo(2);
      }

      // Verify explicit database works for write operations too
      try (Session session = driver.session(config)) {
        session.run("CREATE (n:DbSelectTest {val: 1})").consume();
        final var result = session.run("MATCH (n:DbSelectTest) RETURN n.val AS val");
        assertThat(result.single().get("val").asInt()).isEqualTo(1);

        // Cleanup
        session.run("MATCH (n:DbSelectTest) DELETE n").consume();
      }
    }
  }
}
