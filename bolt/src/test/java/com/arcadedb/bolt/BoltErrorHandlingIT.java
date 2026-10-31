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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.*;
import org.neo4j.driver.exceptions.ClientException;
import org.neo4j.driver.exceptions.FatalDiscoveryException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * Integration tests for BOLT protocol error handling.
 */
public class BoltErrorHandlingIT extends BaseBoltServerTest {

  @Override
  public void setTestConfiguration() {
    super.setTestConfiguration();
    GlobalConfiguration.SERVER_PLUGINS.setValue("Bolt:com.arcadedb.bolt.BoltProtocolPlugin");
  }

  @AfterEach
  @Override
  public void endTest() {
    GlobalConfiguration.SERVER_PLUGINS.setValue("");
    super.endTest();
  }

  private Driver getDriver() {
    return GraphDatabase.driver(
        "bolt://127.0.0.1:" + getServerBoltPort(),
        AuthTokens.basic("root", DEFAULT_PASSWORD_FOR_TESTS),
        Config.builder()
            .withoutEncryption()
            .build()
    );
  }

  @Test
  void testSyntaxErrorReturnsClientException() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        assertThatThrownBy(() -> session.run("THIS IS NOT VALID CYPHER").consume())
            .isInstanceOf(ClientException.class);
      }
    }
  }

  @Test
  void testMissingDatabaseReturnsFatalDiscoveryException() {
    try (Driver driver = getDriver()) {
      // Attempt to access a non-existent database
      final SessionConfig config = SessionConfig.builder().withDatabase("nonexistent_db_12345").build();
      try (Session badSession = driver.session(config)) {
        // Neo4j reports a missing database as Neo.ClientError.Database.DatabaseNotFound, which the driver maps to
        // FatalDiscoveryException
        assertThatThrownBy(() -> badSession.run("RETURN 1").consume())
            .isInstanceOf(FatalDiscoveryException.class);
      }
    }
  }

  @Test
  void testResetAfterFailedStateRestoresReady() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        // Cause a failure
        try {
          session.run("INVALID CYPHER SYNTAX HERE").consume();
          fail("Expected exception");
        } catch (final Exception e) {
          // Expected - session is now in FAILED state internally
        }

        // The driver automatically sends RESET. Verify the session is usable again.
        final var result = session.run("RETURN 'recovered' AS status");
        assertThat(result.single().get("status").asString()).isEqualTo("recovered");
      }
    }
  }

  @Test
  void testMultipleConsecutiveErrorsFollowedByRecovery() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        // Cause multiple errors
        for (int i = 0; i < 3; i++) {
          try {
            session.run("BAD QUERY " + i).consume();
            fail("Expected exception for iteration " + i);
          } catch (final Exception e) {
            // Expected
          }
        }

        // Verify recovery
        final var result = session.run("RETURN 'ok' AS status");
        assertThat(result.single().get("status").asString()).isEqualTo("ok");
      }
    }
  }

  @Test
  void testErrorDuringExplicitTransactionTriggersRollback() {
    try (Driver driver = getDriver()) {
      // First, create a node so we can verify rollback
      try (Session session = driver.session()) {
        session.run("CREATE (n:ErrorTest {name: 'before'})").consume();
      }

      // Now attempt a transaction that fails
      try (Session session = driver.session()) {
        try (Transaction tx = session.beginTransaction()) {
          tx.run("CREATE (n:ErrorTest {name: 'should_be_rolled_back'})").consume();
          // Cause an error within the transaction
          try {
            tx.run("INVALID SYNTAX").consume();
          } catch (final Exception e) {
            // Expected - transaction should be marked for rollback
          }
          tx.rollback();
        }
      }

      // Verify the failed transaction's node was not committed
      try (Session session = driver.session()) {
        final var result = session.run("MATCH (n:ErrorTest {name: 'should_be_rolled_back'}) RETURN count(n) AS cnt");
        assertThat(result.single().get("cnt").asLong()).isEqualTo(0L);
      }

      // Cleanup
      try (Session session = driver.session()) {
        session.run("MATCH (n:ErrorTest) DELETE n").consume();
      }
    }
  }

  @Test
  void testErrorInAutoCommitQueryAllowsSubsequentQueries() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        // First, run a valid query
        session.run("CREATE (n:AutoCommitTest {value: 1})").consume();

        // Run an invalid query
        try {
          session.run("NOT A VALID QUERY").consume();
          fail("Expected exception");
        } catch (final Exception e) {
          // Expected
        }

        // Verify we can still run queries after the error
        final var result = session.run("MATCH (n:AutoCommitTest) RETURN n.value AS val");
        assertThat(result.single().get("val").asInt()).isEqualTo(1);

        // Cleanup
        session.run("MATCH (n:AutoCommitTest) DELETE n").consume();
      }
    }
  }
}
