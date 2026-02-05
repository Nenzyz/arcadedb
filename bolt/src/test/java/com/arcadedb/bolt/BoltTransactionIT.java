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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for BOLT protocol transaction handling.
 */
public class BoltTransactionIT extends BaseGraphServerTest {

  private static final int TEST_BOLT_PORT = 17690;

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
  void testExplicitBeginRunCommit() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        try (Transaction tx = session.beginTransaction()) {
          tx.run("CREATE (n:TxCommitTest {name: 'committed'})").consume();
          tx.commit();
        }
      }

      // Verify node was committed
      try (Session session = driver.session()) {
        final var result = session.run("MATCH (n:TxCommitTest {name: 'committed'}) RETURN count(n) AS cnt");
        assertThat(result.single().get("cnt").asLong()).isEqualTo(1L);

        // Cleanup
        session.run("MATCH (n:TxCommitTest) DELETE n").consume();
      }
    }
  }

  @Test
  void testExplicitBeginRunRollback() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        try (Transaction tx = session.beginTransaction()) {
          tx.run("CREATE (n:TxRollbackTest {name: 'should_not_exist'})").consume();
          tx.rollback();
        }
      }

      // Verify node was NOT committed
      try (Session session = driver.session()) {
        final var result = session.run("MATCH (n:TxRollbackTest {name: 'should_not_exist'}) RETURN count(n) AS cnt");
        assertThat(result.single().get("cnt").asLong()).isEqualTo(0L);
      }
    }
  }

  @Test
  void testSessionExecuteReadWithRetryLogic() {
    try (Driver driver = getDriver()) {
      // Setup data
      try (Session session = driver.session()) {
        session.run("CREATE (n:ReadTest {value: 100})").consume();
      }

      try (Session session = driver.session()) {
        final int value = session.executeRead(tx -> {
          final var result = tx.run("MATCH (n:ReadTest) RETURN n.value AS val");
          return result.single().get("val").asInt();
        });
        assertThat(value).isEqualTo(100);
      }

      // Cleanup
      try (Session session = driver.session()) {
        session.run("MATCH (n:ReadTest) DELETE n").consume();
      }
    }
  }

  @Test
  void testSessionExecuteWriteWithRetryLogic() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        final String name = session.executeWrite(tx -> {
          tx.run("CREATE (n:WriteTest {name: 'written'})").consume();
          final var result = tx.run("MATCH (n:WriteTest {name: 'written'}) RETURN n.name AS name");
          return result.single().get("name").asString();
        });
        assertThat(name).isEqualTo("written");
      }

      // Cleanup
      try (Session session = driver.session()) {
        session.run("MATCH (n:WriteTest) DELETE n").consume();
      }
    }
  }

  @Test
  void testTransactionTimeoutBehavior() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        // Configure a transaction with a timeout
        final TransactionConfig txConfig = TransactionConfig.builder()
            .withTimeout(java.time.Duration.ofSeconds(5))
            .build();
        try (Transaction tx = session.beginTransaction(txConfig)) {
          final var result = tx.run("RETURN 'ok' AS status");
          assertThat(result.single().get("status").asString()).isEqualTo("ok");
          tx.commit();
        }
      }
    }
  }

  @Test
  void testMultipleSequentialTransactionsOnSameSession() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        // Transaction 1: create
        try (Transaction tx = session.beginTransaction()) {
          tx.run("CREATE (n:SeqTest {seq: 1})").consume();
          tx.commit();
        }

        // Transaction 2: create another
        try (Transaction tx = session.beginTransaction()) {
          tx.run("CREATE (n:SeqTest {seq: 2})").consume();
          tx.commit();
        }

        // Transaction 3: read both
        try (Transaction tx = session.beginTransaction()) {
          final var result = tx.run("MATCH (n:SeqTest) RETURN n.seq AS seq ORDER BY n.seq");
          final List<Record> records = result.list();
          assertThat(records).hasSize(2);
          assertThat(records.get(0).get("seq").asInt()).isEqualTo(1);
          assertThat(records.get(1).get("seq").asInt()).isEqualTo(2);
          tx.commit();
        }
      }

      // Cleanup
      try (Session session = driver.session()) {
        session.run("MATCH (n:SeqTest) DELETE n").consume();
      }
    }
  }

  @Test
  void testTransactionIsolationBetweenConcurrentSessions() throws Exception {
    try (Driver driver = getDriver()) {
      // Setup: create a node
      try (Session session = driver.session()) {
        session.run("CREATE (n:IsoTest {value: 'original'})").consume();
      }

      final CountDownLatch session1Started = new CountDownLatch(1);
      final CountDownLatch session2Done = new CountDownLatch(1);
      final AtomicLong session2Count = new AtomicLong(-1);

      // Session 1: begin tx, create node but don't commit yet
      final Thread t1 = new Thread(() -> {
        try (Session session = driver.session()) {
          try (Transaction tx = session.beginTransaction()) {
            tx.run("CREATE (n:IsoTest {value: 'uncommitted'})").consume();
            session1Started.countDown();
            // Wait for session2 to read
            session2Done.await(10, TimeUnit.SECONDS);
            tx.rollback();
          }
        } catch (final Exception e) {
          session1Started.countDown();
        }
      });

      // Session 2: read while session1 has uncommitted data
      final Thread t2 = new Thread(() -> {
        try {
          session1Started.await(10, TimeUnit.SECONDS);
          try (Session session = driver.session()) {
            final var result = session.run("MATCH (n:IsoTest) RETURN count(n) AS cnt");
            session2Count.set(result.single().get("cnt").asLong());
          }
        } catch (final Exception e) {
          // ignore
        } finally {
          session2Done.countDown();
        }
      });

      t1.start();
      t2.start();
      t1.join(15000);
      t2.join(15000);

      // Session 2 should see only the committed node (the 'original' one)
      assertThat(session2Count.get()).isEqualTo(1L);

      // Cleanup
      try (Session session = driver.session()) {
        session.run("MATCH (n:IsoTest) DELETE n").consume();
      }
    }
  }

  @Test
  void testNestedQueriesWithinTransaction() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        try (Transaction tx = session.beginTransaction()) {
          // Create nodes
          tx.run("CREATE (a:NestedTest {name: 'A'})").consume();
          tx.run("CREATE (b:NestedTest {name: 'B'})").consume();

          // Query within same transaction should see both
          final var result = tx.run("MATCH (n:NestedTest) RETURN n.name AS name ORDER BY n.name");
          final List<Record> records = result.list();
          assertThat(records).hasSize(2);
          assertThat(records.get(0).get("name").asString()).isEqualTo("A");
          assertThat(records.get(1).get("name").asString()).isEqualTo("B");

          // Create a relationship between them
          tx.run("MATCH (a:NestedTest {name: 'A'}), (b:NestedTest {name: 'B'}) CREATE (a)-[:KNOWS]->(b)").consume();

          // Verify relationship
          final var relResult = tx.run("MATCH (a:NestedTest)-[:KNOWS]->(b:NestedTest) RETURN a.name AS from, b.name AS to");
          final var relRecord = relResult.single();
          assertThat(relRecord.get("from").asString()).isEqualTo("A");
          assertThat(relRecord.get("to").asString()).isEqualTo("B");

          tx.commit();
        }
      }

      // Cleanup
      try (Session session = driver.session()) {
        session.run("MATCH (n:NestedTest) DETACH DELETE n").consume();
      }
    }
  }
}
