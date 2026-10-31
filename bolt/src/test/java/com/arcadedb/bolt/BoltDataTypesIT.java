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
import org.neo4j.driver.Record;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for BOLT protocol data type round-trips.
 */
public class BoltDataTypesIT extends BaseBoltServerTest {

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
  void testNullRoundTrip() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        final var result = session.run("RETURN $val AS val", Map.of("val", Values.NULL));
        assertThat(result.single().get("val").isNull()).isTrue();
      }
    }
  }

  @Test
  void testBooleanRoundTrip() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        final var result = session.run("RETURN $t AS t, $f AS f",
            Map.of("t", Values.value(true), "f", Values.value(false)));
        final var record = result.single();
        assertThat(record.get("t").asBoolean()).isTrue();
        assertThat(record.get("f").asBoolean()).isFalse();
      }
    }
  }

  @Test
  void testIntegerRoundTrip() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        final var result = session.run(
            "RETURN $tiny AS tiny, $small AS small, $medium AS medium, $large AS large",
            Map.of(
                "tiny", Values.value(42),
                "small", Values.value(1000),
                "medium", Values.value(100000),
                "large", Values.value(Long.MAX_VALUE)
            ));
        final var record = result.single();
        assertThat(record.get("tiny").asLong()).isEqualTo(42L);
        assertThat(record.get("small").asLong()).isEqualTo(1000L);
        assertThat(record.get("medium").asLong()).isEqualTo(100000L);
        assertThat(record.get("large").asLong()).isEqualTo(Long.MAX_VALUE);
      }
    }
  }

  @Test
  void testFloatRoundTrip() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        final var result = session.run(
            "RETURN $pi AS pi, $neg AS neg, $zero AS zero",
            Map.of(
                "pi", Values.value(3.141592653589793),
                "neg", Values.value(-1.0e10),
                "zero", Values.value(0.0)
            ));
        final var record = result.single();
        assertThat(record.get("pi").asDouble()).isEqualTo(3.141592653589793);
        assertThat(record.get("neg").asDouble()).isEqualTo(-1.0e10);
        assertThat(record.get("zero").asDouble()).isEqualTo(0.0);
      }
    }
  }

  @Test
  void testStringRoundTrip() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        final String empty = "";
        final String shortStr = "hello";
        final String unicode = "\u00e9\u00e8\u00ea\u4e16\u754c";
        final String len15 = "a".repeat(15);
        final String len255 = "b".repeat(255);

        final var result = session.run(
            "RETURN $empty AS empty, $short AS short, $unicode AS unicode, $len15 AS len15, $len255 AS len255",
            Map.of(
                "empty", Values.value(empty),
                "short", Values.value(shortStr),
                "unicode", Values.value(unicode),
                "len15", Values.value(len15),
                "len255", Values.value(len255)
            ));
        final var record = result.single();
        assertThat(record.get("empty").asString()).isEqualTo(empty);
        assertThat(record.get("short").asString()).isEqualTo(shortStr);
        assertThat(record.get("unicode").asString()).isEqualTo(unicode);
        assertThat(record.get("len15").asString()).isEqualTo(len15);
        assertThat(record.get("len255").asString()).isEqualTo(len255);
      }
    }
  }

  @Test
  void testListRoundTrip() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        final var result = session.run(
            "RETURN $empty AS empty, $nested AS nested, $mixed AS mixed",
            Map.of(
                "empty", Values.value(Collections.emptyList()),
                "nested", Values.value(List.of(List.of(1, 2), List.of(3, 4))),
                "mixed", Values.value(new Object[]{"text", 42, true})
            ));
        final var record = result.single();
        assertThat(record.get("empty").asList()).isEmpty();
        assertThat(record.get("nested").asList()).hasSize(2);
        assertThat(record.get("mixed").asList()).containsExactly("text", 42L, true);
      }
    }
  }

  @Test
  void testMapRoundTrip() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        final var result = session.run(
            "RETURN $empty AS empty, $nested AS nested",
            Map.of(
                "empty", Values.value(Collections.emptyMap()),
                "nested", Values.value(Map.of("a", 1, "b", Map.of("c", 2)))
            ));
        final var record = result.single();
        assertThat(record.get("empty").asMap()).isEmpty();
        final var nestedMap = record.get("nested").asMap();
        assertThat(nestedMap.get("a")).isEqualTo(1L);
      }
    }
  }

  @Test
  void testNodeAndRelationshipRoundTrip() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        // Create and return all elements in same query
        final var result = session.run(
            "CREATE (a:TypeTestA {name: 'A'})-[r:KNOWS {since: 2020}]->(b:TypeTestB {name: 'B'}) RETURN a, r, b");
        final var record = result.single();

        // Test node
        final Node nodeA = record.get("a").asNode();
        assertThat(nodeA.get("name").asString()).isEqualTo("A");
        assertThat(nodeA.labels()).contains("TypeTestA");

        final Node nodeB = record.get("b").asNode();
        assertThat(nodeB.get("name").asString()).isEqualTo("B");
        assertThat(nodeB.labels()).contains("TypeTestB");

        // Test relationship
        final Relationship rel = record.get("r").asRelationship();
        assertThat(rel.type()).isEqualTo("KNOWS");
        assertThat(rel.get("since").asLong()).isEqualTo(2020L);

        // Cleanup
        session.run("MATCH (n:TypeTestA) DETACH DELETE n").consume();
        session.run("MATCH (n:TypeTestB) DELETE n").consume();
      }
    }
  }
}
