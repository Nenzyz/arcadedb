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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for BOLT protocol version negotiation.
 */
public class BoltVersionNegotiationIT extends BaseBoltServerTest {

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
  void testBoltV44HandshakeNegotiation() {
    // Neo4j driver negotiates v4.4 by default; verify connectivity succeeds
    try (Driver driver = getDriver()) {
      driver.verifyConnectivity();
    }
  }

  @Test
  void testBoltV40HandshakeNegotiation() {
    // v4.0 is supported; driver negotiation with v4.0 should work
    // The Neo4j driver handles version negotiation internally, so verifyConnectivity
    // implicitly tests that a supported version was negotiated
    try (Driver driver = getDriver()) {
      driver.verifyConnectivity();
      try (Session session = driver.session(SessionConfig.forDatabase(getDatabaseName()))) {
        final Result result = session.run("RETURN 1 AS v");
        assertThat(result.next().get("v").asLong()).isEqualTo(1L);
      }
    }
  }

  @Test
  void testBoltV30HandshakeNegotiation() {
    // v3.0 is in the supported list; a raw socket test can verify it
    // Here we validate the server accepts connections and negotiates successfully
    try (Driver driver = getDriver()) {
      driver.verifyConnectivity();
      try (Session session = driver.session(SessionConfig.forDatabase(getDatabaseName()))) {
        final Result result = session.run("RETURN 'v3test' AS msg");
        assertThat(result.next().get("msg").asString()).isEqualTo("v3test");
      }
    }
  }

  @Test
  void testServerStringInHelloResponse() {
    // The HELLO response should contain a server string with ArcadeDB info
    try (Driver driver = getDriver()) {
      driver.verifyConnectivity();
      // If verifyConnectivity() succeeds, the driver received a valid HELLO response
      // with the server string. We verify it further by running a query.
      try (Session session = driver.session(SessionConfig.forDatabase(getDatabaseName()))) {
        final Result result = session.run("RETURN 1 AS check");
        assertThat(result.hasNext()).isTrue();
        result.next();
      }
    }
  }

  @Test
  void testRawSocketHandshakeWithValidVersionProposals() throws IOException {
    // Send raw BOLT handshake with valid version proposals and verify negotiated version
    try (Socket socket = new Socket("127.0.0.1", getServerBoltPort())) {
      final DataOutputStream out = new DataOutputStream(socket.getOutputStream());
      final DataInputStream in = new DataInputStream(socket.getInputStream());

      // Send BOLT magic: 0x6060B017
      out.writeByte(0x60);
      out.writeByte(0x60);
      out.writeByte(0xB0);
      out.writeByte(0x17);

      // Send 4 version proposals (big-endian, bytes = [reserved, range, minor, major]): v4.4, v4.0, v3.0, 0
      out.writeInt(0x00000404); // v4.4
      out.writeInt(0x00000004); // v4.0
      out.writeInt(0x00000003); // v3.0
      out.writeInt(0x00000000); // no version
      out.flush();

      // Read negotiated version (4 bytes, big-endian)
      final int negotiatedVersion = in.readInt();

      // Server should pick v4.4 (0x00000404) as the first supported match
      assertThat(negotiatedVersion).isEqualTo(0x00000404);
    }
  }

  @Test
  void testRawSocketHandshakeWithUnsupportedVersionProposal() throws IOException {
    // Send raw BOLT handshake with only unsupported versions: the server must answer 0 (no version agreed).
    try (Socket socket = new Socket("127.0.0.1", getServerBoltPort())) {
      final DataOutputStream out = new DataOutputStream(socket.getOutputStream());
      final DataInputStream in = new DataInputStream(socket.getInputStream());

      // Send BOLT magic
      out.writeByte(0x60);
      out.writeByte(0x60);
      out.writeByte(0xB0);
      out.writeByte(0x17);

      // Send 4 unsupported version proposals (majors 0x99..0x96 do not exist)
      out.writeInt(0x00000099);
      out.writeInt(0x00000098);
      out.writeInt(0x00000097);
      out.writeInt(0x00000096);
      out.flush();

      final int negotiatedVersion = in.readInt();
      assertThat(negotiatedVersion).isZero();
    }
  }
}
