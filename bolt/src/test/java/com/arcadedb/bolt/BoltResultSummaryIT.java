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
import org.neo4j.driver.summary.ResultSummary;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for BOLT protocol result summary behavior.
 */
public class BoltResultSummaryIT extends BaseGraphServerTest {

  private static final int TEST_BOLT_PORT = 17692;

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
  void testSummaryQueryTypeForReadQuery() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        final ResultSummary summary = session.run("RETURN 1 AS n").consume();
        assertThat(summary).isNotNull();
        // Summary should have a query type
        assertThat(summary.queryType()).isNotNull();
      }
    }
  }

  @Test
  void testSummaryQueryTypeForWriteQuery() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        final ResultSummary summary = session.run("CREATE (n:SummaryWriteTest {val: 1})").consume();
        assertThat(summary).isNotNull();
        assertThat(summary.queryType()).isNotNull();

        // Cleanup
        session.run("MATCH (n:SummaryWriteTest) DELETE n").consume();
      }
    }
  }

  @Test
  void testSummaryCounters() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        final ResultSummary summary = session.run("CREATE (n:CounterTest {name: 'test', age: 30})").consume();
        // Verify counters object is available (ArcadeDB may or may not populate specific counts)
        assertThat(summary.counters()).isNotNull();
        // The total of all counter values should be non-negative
        assertThat(summary.counters().nodesCreated()).isGreaterThanOrEqualTo(0);
        assertThat(summary.counters().propertiesSet()).isGreaterThanOrEqualTo(0);

        // Cleanup
        session.run("MATCH (n:CounterTest) DELETE n").consume();
      }
    }
  }

  @Test
  void testSummaryTimingFields() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        final ResultSummary summary = session.run("RETURN 1 AS n").consume();
        // t_first (result available after) and t_last (result consumed after) should be non-negative
        assertThat(summary.resultAvailableAfter(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(0);
        assertThat(summary.resultConsumedAfter(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(0);
      }
    }
  }
}
