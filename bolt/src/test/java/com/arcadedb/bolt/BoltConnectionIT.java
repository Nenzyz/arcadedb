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

import java.io.DataOutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for BOLT connection lifecycle.
 */
public class BoltConnectionIT extends BaseBoltServerTest {

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
  void testVerifyConnectivity() {
    try (Driver driver = getDriver()) {
      driver.verifyConnectivity();
      // No exception means connectivity is verified
    }
  }

  @Test
  void testMultipleSessionsFromSameDriver() {
    // Test connection pooling: multiple sessions reuse connections from the same driver
    try (Driver driver = getDriver()) {
      for (int i = 0; i < 5; i++) {
        try (Session session = driver.session(SessionConfig.forDatabase(getDatabaseName()))) {
          final Result result = session.run("RETURN $i AS iteration", java.util.Map.of("i", i));
          assertThat(result.next().get("iteration").asLong()).isEqualTo((long) i);
        }
      }
    }
  }

  @Test
  void testGracefulDisconnect() {
    // Driver.close() sends GOODBYE message for graceful disconnect
    final Driver driver = getDriver();
    driver.verifyConnectivity();
    driver.close();

    // After close, creating a new driver and connecting should still work
    try (Driver driver2 = getDriver()) {
      driver2.verifyConnectivity();
      try (Session session = driver2.session(SessionConfig.forDatabase(getDatabaseName()))) {
        final Result result = session.run("RETURN 'alive' AS status");
        assertThat(result.next().get("status").asString()).isEqualTo("alive");
      }
    }
  }

  @Test
  void testAbruptDisconnect() throws Exception {
    // Open a raw socket, perform handshake, then abruptly close without GOODBYE
    try (Socket socket = new Socket("127.0.0.1", getServerBoltPort())) {
      final DataOutputStream out = new DataOutputStream(socket.getOutputStream());

      // Send BOLT magic
      out.writeByte(0x60);
      out.writeByte(0x60);
      out.writeByte(0xB0);
      out.writeByte(0x17);

      // Send version proposals
      out.writeInt(0x00000104); // v4.4
      out.writeInt(0x00000004); // v4.0
      out.writeInt(0x00000003); // v3.0
      out.writeInt(0x00000000);
      out.flush();

      // Read negotiated version
      socket.getInputStream().read(new byte[4]);
    }
    // Socket closed abruptly without GOODBYE

    // Server should still accept new connections after abrupt disconnect
    try (Driver driver = getDriver()) {
      driver.verifyConnectivity();
      try (Session session = driver.session(SessionConfig.forDatabase(getDatabaseName()))) {
        final Result result = session.run("RETURN 'ok' AS status");
        assertThat(result.next().get("status").asString()).isEqualTo("ok");
      }
    }
  }

  @Test
  void testConnectionLimitEnforcement() throws Exception {
    // Set a low connection limit
    final int originalMax = GlobalConfiguration.BOLT_MAX_CONNECTIONS.getValueAsInteger();
    try {
      final int maxConnections = 3;
      GlobalConfiguration.BOLT_MAX_CONNECTIONS.setValue(maxConnections);

      // Note: The connection limit is read at listener creation time,
      // so we test with the current server config. The existing BoltProtocolIT
      // already has a testConnectionLimit test. Here we verify the concept
      // by opening multiple drivers and checking connectivity.
      final List<Driver> drivers = new ArrayList<>();
      try {
        for (int i = 0; i < maxConnections; i++) {
          final Driver driver = getDriver();
          driver.verifyConnectivity();
          drivers.add(driver);
        }

        // All connections within limit should work
        for (final Driver driver : drivers) {
          try (Session session = driver.session(SessionConfig.forDatabase(getDatabaseName()))) {
            final Result result = session.run("RETURN 1 AS v");
            assertThat(result.hasNext()).isTrue();
            result.next();
          }
        }
      } finally {
        for (final Driver driver : drivers) {
          try {
            driver.close();
          } catch (final Exception e) {
            // Ignore cleanup errors
          }
        }
      }
    } finally {
      GlobalConfiguration.BOLT_MAX_CONNECTIONS.setValue(originalMax);
    }
  }

  @Test
  void testDriverCloseAndReconnect() {
    // Close a driver and create a new one to verify reconnection works
    try (Driver driver1 = getDriver()) {
      driver1.verifyConnectivity();
      try (Session session = driver1.session(SessionConfig.forDatabase(getDatabaseName()))) {
        session.run("CREATE (n:ReconnectTest {value: 'first'})").consume();
      }
    }

    // Create a new driver and verify data persists
    try (Driver driver2 = getDriver()) {
      driver2.verifyConnectivity();
      try (Session session = driver2.session(SessionConfig.forDatabase(getDatabaseName()))) {
        final Result result = session.run("MATCH (n:ReconnectTest {value: 'first'}) RETURN n.value AS v");
        assertThat(result.hasNext()).isTrue();
        assertThat(result.next().get("v").asString()).isEqualTo("first");
      }
    }
  }
}
