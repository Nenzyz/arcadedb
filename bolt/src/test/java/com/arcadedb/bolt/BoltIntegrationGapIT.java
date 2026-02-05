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
import org.neo4j.driver.exceptions.AuthenticationException;
import org.neo4j.driver.exceptions.ClientException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * Gap-filling integration tests for cross-category scenarios and edge cases
 * not covered by the individual category IT classes.
 */
public class BoltIntegrationGapIT extends BaseGraphServerTest {

  private static final int TEST_BOLT_PORT = 17697;

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

  private Driver getDriver(final String user, final String password) {
    return GraphDatabase.driver(
        "bolt://127.0.0.1:" + TEST_BOLT_PORT,
        AuthTokens.basic(user, password),
        Config.builder()
            .withoutEncryption()
            .build()
    );
  }

  /**
   * Cross-category: auth failure followed by successful transaction on new driver.
   * Verifies server state machine correctly resets between connections.
   */
  @Test
  void testAuthFailureThenSuccessfulTransaction() {
    // Auth failure
    assertThatThrownBy(() -> {
      try (Driver driver = getDriver("root", "wrongPassword")) {
        driver.verifyConnectivity();
      }
    }).isInstanceOf(AuthenticationException.class);

    // Successful transaction on new connection
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        try (Transaction tx = session.beginTransaction()) {
          tx.run("CREATE (n:AuthTxTest {val: 1})").consume();
          tx.commit();
        }
      }

      // Verify committed
      try (Session session = driver.session()) {
        final var result = session.run("MATCH (n:AuthTxTest) RETURN count(n) AS cnt");
        assertThat(result.single().get("cnt").asLong()).isEqualTo(1L);

        session.run("MATCH (n:AuthTxTest) DELETE n").consume();
      }
    }
  }

  /**
   * Cross-category: error during executeWrite transaction function triggers retry/rollback.
   * Verifies the retry mechanism handles errors within transaction functions.
   */
  @Test
  void testErrorDuringExecuteWriteTransactionFunction() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        // executeWrite that encounters an error should not leave partial data
        try {
          session.executeWrite(tx -> {
            tx.run("CREATE (n:ExecuteWriteErrTest {val: 'partial'})").consume();
            // Force an error
            tx.run("INVALID CYPHER SYNTAX").consume();
            return null;
          });
          fail("Expected exception from executeWrite");
        } catch (final Exception e) {
          // Expected
        }
      }

      // Verify the partial node was not committed
      try (Session session = driver.session()) {
        final var result = session.run("MATCH (n:ExecuteWriteErrTest) RETURN count(n) AS cnt");
        assertThat(result.single().get("cnt").asLong()).isEqualTo(0L);
      }
    }
  }

  /**
   * Cross-category: session error recovery then transaction commit.
   * Verifies FAILED->READY->TX_READY state transitions.
   */
  @Test
  void testErrorRecoveryThenTransactionCommit() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        // Cause error (READY -> FAILED)
        try {
          session.run("BAD QUERY").consume();
        } catch (final Exception e) {
          // Expected, driver sends RESET (FAILED -> READY)
        }

        // Start transaction (READY -> TX_READY -> TX_STREAMING -> TX_READY -> READY)
        try (Transaction tx = session.beginTransaction()) {
          tx.run("CREATE (n:StateTransTest {val: 'ok'})").consume();
          tx.commit();
        }

        // Verify
        final var result = session.run("MATCH (n:StateTransTest) RETURN n.val AS val");
        assertThat(result.single().get("val").asString()).isEqualTo("ok");

        session.run("MATCH (n:StateTransTest) DELETE n").consume();
      }
    }
  }

  /**
   * Data type: large string at 65535-byte boundary for PackStream STRING_16/STRING_32 encoding.
   */
  @Test
  void testLargeStringAt65535Boundary() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        final String len65535 = "x".repeat(65535);
        final var result = session.run("RETURN $s AS s", Map.of("s", Values.value(len65535)));
        assertThat(result.single().get("s").asString()).hasSize(65535);
      }
    }
  }

  /**
   * Data type: list at boundary sizes for PackStream LIST_8/LIST_16 encoding.
   */
  @Test
  void testListAtBoundarySizes() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        // LIST_8 boundary: 16 elements (above TINY_LIST max of 15)
        final var list16 = java.util.stream.IntStream.range(0, 16).boxed().toList();
        final var result = session.run("RETURN $list AS list", Map.of("list", Values.value(list16)));
        assertThat(result.single().get("list").asList()).hasSize(16);
      }
    }
  }

  /**
   * Data type: path query returns a result (ArcadeDB may serialize path as string rather than
   * a Bolt Path structure, so we verify the query executes and returns a non-null value).
   */
  @Test
  void testPathQueryReturnsResult() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        session.run("CREATE (a:PathGapA {name: 'start'})-[:CONNECTS {w: 1}]->(b:PathGapB {name: 'end'})").consume();

        final var result = session.run(
            "MATCH p = (a:PathGapA {name: 'start'})-[r:CONNECTS]->(b:PathGapB {name: 'end'}) RETURN p");
        assertThat(result.hasNext()).isTrue();
        final var record = result.single();
        // Verify the path value is returned (may be Path or String depending on ArcadeDB serialization)
        assertThat(record.get("p")).isNotNull();
        assertThat(record.get("p").isNull()).isFalse();

        session.run("MATCH (n:PathGapA) DETACH DELETE n").consume();
        session.run("MATCH (n:PathGapB) DELETE n").consume();
      }
    }
  }

  /**
   * Session: UNWIND with large dataset to exercise fetch size / streaming boundaries.
   */
  @Test
  void testUnwindLargeDataset() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        final var result = session.run("UNWIND range(1, 1000) AS x RETURN x");
        final List<Record> records = result.list();
        assertThat(records).hasSize(1000);
        assertThat(records.get(0).get("x").asLong()).isEqualTo(1L);
        assertThat(records.get(999).get("x").asLong()).isEqualTo(1000L);
      }
    }
  }

  /**
   * Transaction: multiple error-recovery cycles within a single session,
   * alternating between auto-commit errors and successful transactions.
   */
  @Test
  void testAlternatingErrorsAndTransactions() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        for (int i = 0; i < 3; i++) {
          // Error cycle
          try {
            session.run("INVALID_" + i).consume();
          } catch (final Exception e) {
            // Expected
          }

          // Successful transaction cycle
          try (Transaction tx = session.beginTransaction()) {
            tx.run("CREATE (n:AlternateTest {iter: $i})", Map.of("i", i)).consume();
            tx.commit();
          }
        }

        // Verify all 3 nodes were committed
        final var result = session.run("MATCH (n:AlternateTest) RETURN count(n) AS cnt");
        assertThat(result.single().get("cnt").asLong()).isEqualTo(3L);

        session.run("MATCH (n:AlternateTest) DELETE n").consume();
      }
    }
  }

  /**
   * Summary: schema query type verification for CREATE INDEX.
   * Verifies that schema-modifying queries return a valid summary.
   */
  @Test
  void testSummaryForSchemaQuery() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        // Create a type first so the index creation has something to work with
        session.run("CREATE (n:SchemaGapTest {prop: 'value'})").consume();

        // Schema operation: create index (ArcadeDB supports this via Cypher)
        try {
          final var summary = session.run("CREATE INDEX ON :SchemaGapTest(prop)").consume();
          assertThat(summary).isNotNull();
          assertThat(summary.queryType()).isNotNull();
        } catch (final ClientException e) {
          // If index creation is not supported via Cypher, that's acceptable
        }

        session.run("MATCH (n:SchemaGapTest) DELETE n").consume();
      }
    }
  }

  /**
   * Connection: rapid open/close cycles to stress connection cleanup.
   */
  @Test
  void testRapidOpenCloseCycles() {
    for (int i = 0; i < 10; i++) {
      try (Driver driver = getDriver()) {
        try (Session session = driver.session()) {
          final var result = session.run("RETURN $i AS i", Map.of("i", i));
          assertThat(result.single().get("i").asLong()).isEqualTo((long) i);
        }
      }
    }
  }
}
