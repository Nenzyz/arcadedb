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
import com.arcadedb.bolt.message.BoltMessage;
import com.arcadedb.bolt.message.HelloMessage;
import com.arcadedb.bolt.message.RouteMessage;
import com.arcadedb.bolt.packstream.PackStreamReader;
import com.arcadedb.bolt.packstream.PackStreamWriter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for BOLT protocol routing table behavior.
 * ArcadeDB is single-server for Bolt, so the routing table contains the same server for all roles.
 * Tests use raw sockets to directly verify ROUTE message handling at the protocol level.
 * <p>
 * Note: The neo4j:// driver scheme is not tested here because the Neo4j Java driver 5.x
 * attempts routing discovery against the "system" database, which ArcadeDB does not have.
 * This is a known limitation. The ROUTE message itself works correctly at the protocol level.
 */
public class BoltRoutingIT extends BaseBoltServerTest {

  @Override
  public void setTestConfiguration() {
    super.setTestConfiguration();
    GlobalConfiguration.SERVER_PLUGINS.setValue("Bolt:com.arcadedb.bolt.BoltProtocolPlugin");
  }

  @AfterEach
  @Override
  public void endTest() {
    GlobalConfiguration.SERVER_PLUGINS.setValue("");
    GlobalConfiguration.BOLT_ROUTING_TTL.reset();
    super.endTest();
  }

  /**
   * Performs Bolt handshake + HELLO on a raw socket, returning the socket in READY state.
   */
  private Socket connectAndAuthenticate() throws Exception {
    final Socket socket = new Socket("127.0.0.1", getServerBoltPort());
    final DataOutputStream out = new DataOutputStream(socket.getOutputStream());
    final DataInputStream in = new DataInputStream(socket.getInputStream());

    // Send BOLT magic: 0x6060B017
    out.writeInt(0x6060B017);
    // Propose v4.1, v4.0, v3.0, 0x00000000
    out.writeInt(0x00000104); // v4.1
    out.writeInt(0x00000004); // v4.0
    out.writeInt(0x00000003); // v3.0
    out.writeInt(0x00000000); // no version
    out.flush();

    // Read negotiated version
    final int negotiated = in.readInt();
    assertThat(negotiated).isNotEqualTo(0);

    // Send HELLO with basic auth
    final Map<String, Object> extra = new LinkedHashMap<>();
    extra.put("user_agent", "ArcadeDB-BoltRoutingIT/1.0");
    extra.put("scheme", "basic");
    extra.put("principal", "root");
    extra.put("credentials", DEFAULT_PASSWORD_FOR_TESTS);

    final HelloMessage hello = new HelloMessage(extra);
    sendMessage(out, hello);

    // Read SUCCESS response
    final Map<String, Object> helloResponse = readSuccessResponse(in);
    assertThat(helloResponse).containsKey("server");

    return socket;
  }

  private void sendMessage(final DataOutputStream out, final BoltMessage message) throws Exception {
    final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    final PackStreamWriter writer = new PackStreamWriter(buffer);
    message.writeTo(writer);
    final byte[] data = buffer.toByteArray();

    // Write as chunked message: 2-byte length + data + 0x0000 end marker
    out.writeShort(data.length);
    out.write(data);
    out.writeShort(0); // end marker
    out.flush();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readSuccessResponse(final DataInputStream in) throws Exception {
    // Read chunk header (2 bytes length)
    final int chunkSize = in.readUnsignedShort();
    assertThat(chunkSize).isGreaterThan(0);

    final byte[] chunk = new byte[chunkSize];
    in.readFully(chunk);

    // Read end marker
    final int endMarker = in.readUnsignedShort();
    assertThat(endMarker).isEqualTo(0);

    // Parse the response using PackStreamReader
    final PackStreamReader reader = new PackStreamReader(chunk);
    final Object value = reader.readValue();
    assertThat(value).isInstanceOf(PackStreamReader.StructureValue.class);

    final PackStreamReader.StructureValue structure = (PackStreamReader.StructureValue) value;
    // SUCCESS signature is 0x70
    assertThat(structure.getSignature() & 0xFF).isEqualTo(0x70);

    // The first field of SUCCESS is the metadata map
    return (Map<String, Object>) structure.getField(0);
  }

  @Test
  void testRouteMessageReturnsValidRoutingTableWithAllRoles() throws Exception {
    try (Socket socket = connectAndAuthenticate()) {
      final DataOutputStream out = new DataOutputStream(socket.getOutputStream());
      final DataInputStream in = new DataInputStream(socket.getInputStream());

      // Send ROUTE message with the test database
      final RouteMessage route = new RouteMessage(Map.of(), List.of(), getDatabaseName());
      sendMessage(out, route);

      // Read SUCCESS response containing the routing table
      final Map<String, Object> response = readSuccessResponse(in);
      assertThat(response).containsKey("rt");

      @SuppressWarnings("unchecked")
      final Map<String, Object> rt = (Map<String, Object>) response.get("rt");
      assertThat(rt).containsKey("ttl");
      assertThat(rt).containsKey("db");
      assertThat(rt).containsKey("servers");

      @SuppressWarnings("unchecked")
      final List<Map<String, Object>> servers = (List<Map<String, Object>>) rt.get("servers");
      assertThat(servers).hasSize(3);

      // Verify all three roles are present: WRITE, READ, ROUTE
      final List<String> roles = servers.stream()
          .map(s -> (String) s.get("role"))
          .toList();
      assertThat(roles).containsExactlyInAnyOrder("WRITE", "READ", "ROUTE");

      // Each server entry should have a non-empty addresses list advertising the port the server bound
      final String expectedPortSuffix = ":" + getServerBoltPort();
      for (final Map<String, Object> server : servers) {
        @SuppressWarnings("unchecked")
        final List<String> addresses = (List<String>) server.get("addresses");
        assertThat(addresses).isNotEmpty();
        assertThat(addresses).anyMatch(address -> address.endsWith(expectedPortSuffix));
      }
    }
  }

  @Test
  void testRouteMessageReturnsSameServerForAllRoles() throws Exception {
    // ArcadeDB is single-server for Bolt, so all roles should point to the same address
    try (Socket socket = connectAndAuthenticate()) {
      final DataOutputStream out = new DataOutputStream(socket.getOutputStream());
      final DataInputStream in = new DataInputStream(socket.getInputStream());

      final RouteMessage route = new RouteMessage(Map.of(), List.of(), getDatabaseName());
      sendMessage(out, route);

      final Map<String, Object> response = readSuccessResponse(in);
      @SuppressWarnings("unchecked")
      final Map<String, Object> rt = (Map<String, Object>) response.get("rt");
      @SuppressWarnings("unchecked")
      final List<Map<String, Object>> servers = (List<Map<String, Object>>) rt.get("servers");

      // All server entries should have the same address (single-server)
      @SuppressWarnings("unchecked")
      final List<String> writeAddresses = (List<String>) servers.get(0).get("addresses");
      for (final Map<String, Object> server : servers) {
        @SuppressWarnings("unchecked")
        final List<String> addresses = (List<String>) server.get("addresses");
        assertThat(addresses).isEqualTo(writeAddresses);
      }
    }
  }

  @Test
  void testRoutingTtlFromGlobalConfiguration() throws Exception {
    // Set a custom TTL and verify it appears in the routing table response
    final long customTtl = 60L;
    GlobalConfiguration.BOLT_ROUTING_TTL.setValue(customTtl);

    try (Socket socket = connectAndAuthenticate()) {
      final DataOutputStream out = new DataOutputStream(socket.getOutputStream());
      final DataInputStream in = new DataInputStream(socket.getInputStream());

      final RouteMessage route = new RouteMessage(Map.of(), List.of(), getDatabaseName());
      sendMessage(out, route);

      final Map<String, Object> response = readSuccessResponse(in);
      @SuppressWarnings("unchecked")
      final Map<String, Object> rt = (Map<String, Object>) response.get("rt");

      assertThat(((Number) rt.get("ttl")).longValue()).isEqualTo(customTtl);
    }
  }
}
