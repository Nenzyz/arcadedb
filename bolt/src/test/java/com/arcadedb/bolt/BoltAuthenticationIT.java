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
import org.neo4j.driver.exceptions.AuthenticationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for BOLT protocol authentication.
 */
public class BoltAuthenticationIT extends BaseBoltServerTest {

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

  private Driver getDriver(final String user, final String password) {
    return GraphDatabase.driver(
        "bolt://127.0.0.1:" + getServerBoltPort(),
        AuthTokens.basic(user, password),
        Config.builder()
            .withoutEncryption()
            .build()
    );
  }

  @Test
  void testValidBasicAuthSucceeds() {
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        final var result = session.run("RETURN 1 AS n");
        assertThat(result.single().get("n").asInt()).isEqualTo(1);
      }
    }
  }

  @Test
  void testInvalidPasswordReturnsAuthenticationException() {
    assertThatThrownBy(() -> {
      try (Driver driver = getDriver("root", "wrongPassword")) {
        driver.verifyConnectivity();
      }
    }).isInstanceOf(AuthenticationException.class);
  }

  @Test
  void testInvalidUsernameReturnsAuthenticationException() {
    assertThatThrownBy(() -> {
      try (Driver driver = getDriver("nonexistentUser", DEFAULT_PASSWORD_FOR_TESTS)) {
        driver.verifyConnectivity();
      }
    }).isInstanceOf(AuthenticationException.class);
  }

  @Test
  void testEmptyCredentialsBehavior() {
    assertThatThrownBy(() -> {
      try (Driver driver = getDriver("", "")) {
        driver.verifyConnectivity();
      }
    }).isInstanceOf(AuthenticationException.class);
  }

  @Test
  void testResetRecoveryAfterAuthFailure() {
    // First, verify auth failure with bad credentials
    assertThatThrownBy(() -> {
      try (Driver driver = getDriver("root", "wrongPassword")) {
        driver.verifyConnectivity();
      }
    }).isInstanceOf(AuthenticationException.class);

    // Then verify good credentials still work (new connection/driver)
    try (Driver driver = getDriver()) {
      try (Session session = driver.session()) {
        final var result = session.run("RETURN 42 AS answer");
        assertThat(result.single().get("answer").asInt()).isEqualTo(42);
      }
    }
  }
}
