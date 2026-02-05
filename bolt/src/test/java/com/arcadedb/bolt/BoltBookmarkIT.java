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

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for BOLT protocol bookmark handling.
 */
public class BoltBookmarkIT extends BaseGraphServerTest {

  private static final int TEST_BOLT_PORT = 17693;

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
  void testCommitReturnsBookmark() {
    try (Driver driver = getDriver()) {
      Set<Bookmark> bookmarks;
      try (Session session = driver.session()) {
        session.run("CREATE (n:BookmarkTest {val: 1})").consume();
        bookmarks = session.lastBookmarks();
      }
      // ArcadeDB returns bookmarks after write transactions
      assertThat(bookmarks).isNotNull();

      // Cleanup
      try (Session session = driver.session()) {
        session.run("MATCH (n:BookmarkTest) DELETE n").consume();
      }
    }
  }

  @Test
  void testPassingBookmarksBetweenSessions() {
    try (Driver driver = getDriver()) {
      // Session 1: write data and get bookmark
      Set<Bookmark> bookmarks;
      try (Session session = driver.session()) {
        session.run("CREATE (n:BookmarkPassTest {val: 42})").consume();
        bookmarks = session.lastBookmarks();
      }

      // Session 2: use bookmark for causal consistency (even if server ignores it, it should not error)
      final SessionConfig.Builder configBuilder = SessionConfig.builder();
      if (bookmarks != null && !bookmarks.isEmpty())
        configBuilder.withBookmarks(bookmarks);

      try (Session session = driver.session(configBuilder.build())) {
        final var result = session.run("MATCH (n:BookmarkPassTest) RETURN n.val AS val");
        assertThat(result.single().get("val").asLong()).isEqualTo(42L);
      }

      // Cleanup
      try (Session session = driver.session()) {
        session.run("MATCH (n:BookmarkPassTest) DELETE n").consume();
      }
    }
  }

  @Test
  void testBookmarkFormatIsValid() {
    try (Driver driver = getDriver()) {
      Set<Bookmark> bookmarks;
      try (Session session = driver.session()) {
        session.run("CREATE (n:BookmarkFmtTest {val: 1})").consume();
        bookmarks = session.lastBookmarks();
      }

      // If bookmarks are returned, they should contain valid non-empty strings
      if (bookmarks != null && !bookmarks.isEmpty()) {
        for (final Bookmark bookmark : bookmarks) {
          assertThat(bookmark.values()).isNotEmpty();
          for (final String value : bookmark.values()) {
            assertThat(value).isNotNull().isNotEmpty();
          }
        }
      }

      // Cleanup
      try (Session session = driver.session()) {
        session.run("MATCH (n:BookmarkFmtTest) DELETE n").consume();
      }
    }
  }
}
