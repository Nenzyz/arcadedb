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
package com.arcadedb.redis;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RedisList}, {@link RedisSet}, {@link RedisGlob} and the expiry bookkeeping of {@link RedisKeyspace}, without a server.
 */
class RedisCollectionsTest {

  @Test
  void listBehavesLikeAnArrayListAcrossWrapAroundAndGrowth() {
    final RedisList list = new RedisList();
    final List<String> reference = new ArrayList<>();
    final Random random = new Random(42);

    for (int i = 0; i < 5_000; i++) {
      final String element = Integer.toString(i);
      switch (random.nextInt(7)) {
      case 0 -> {
        list.addFirst(element);
        reference.addFirst(element);
      }
      case 1, 2 -> {
        list.addLast(element);
        reference.addLast(element);
      }
      case 3 -> assertThat(list.pollFirst()).isEqualTo(reference.isEmpty() ? null : reference.removeFirst());
      case 4 -> assertThat(list.pollLast()).isEqualTo(reference.isEmpty() ? null : reference.removeLast());
      case 5 -> {
        if (!reference.isEmpty()) {
          final int index = random.nextInt(reference.size());
          list.insert(index, element);
          reference.add(index, element);
        }
      }
      default -> {
        if (!reference.isEmpty()) {
          final int index = random.nextInt(reference.size());
          assertThat(list.remove(index)).isEqualTo(reference.remove(index));
        }
      }
      }
      assertThat(list.size()).isEqualTo(reference.size());
    }

    for (int i = 0; i < reference.size(); i++)
      assertThat(list.get(i)).isEqualTo(reference.get(i));
    assertThat(list.range(0, reference.size())).containsExactlyElementsOf(reference);
  }

  @Test
  void listTrimKeepsTheInclusiveRange() {
    final RedisList list = new RedisList();
    for (int i = 0; i < 10; i++)
      list.addFirst(Integer.toString(i));
    list.trim(2, 5);
    assertThat(list.range(0, list.size())).containsExactly("7", "6", "5", "4");
  }

  @Test
  void setPicksAndRemovesInConstantTime() {
    final RedisSet set = new RedisSet();
    for (int i = 0; i < 100; i++)
      assertThat(set.add("m" + i)).isTrue();
    assertThat(set.add("m5")).isFalse();
    assertThat(set.remove("m5")).isTrue();
    assertThat(set.remove("m5")).isFalse();
    assertThat(set.size()).isEqualTo(99);

    final Set<String> popped = new HashSet<>();
    final Random random = new Random(7);
    while (set.size() > 0) {
      final String picked = set.random(random);
      assertThat(set.contains(picked)).isTrue();
      assertThat(set.remove(picked)).isTrue();
      assertThat(popped.add(picked)).isTrue();
    }
    assertThat(popped).hasSize(99).doesNotContain("m5");
    assertThat(set.random(random)).isNull();
  }

  @Test
  void globMatchesLikeRedis() {
    assertThat(RedisGlob.matches("*", "")).isTrue();
    assertThat(RedisGlob.matches("h?llo", "hello")).isTrue();
    assertThat(RedisGlob.matches("h?llo", "hllo")).isFalse();
    assertThat(RedisGlob.matches("h*llo", "heeeello")).isTrue();
    assertThat(RedisGlob.matches("h[ae]llo", "hallo")).isTrue();
    assertThat(RedisGlob.matches("h[ae]llo", "hillo")).isFalse();
    assertThat(RedisGlob.matches("h[^e]llo", "hallo")).isTrue();
    assertThat(RedisGlob.matches("h[^e]llo", "hello")).isFalse();
    assertThat(RedisGlob.matches("h[a-b]llo", "hbllo")).isTrue();
    assertThat(RedisGlob.matches("h[a-b]llo", "hcllo")).isFalse();
    assertThat(RedisGlob.matches("h\\*llo", "h*llo")).isTrue();
    assertThat(RedisGlob.matches("h\\*llo", "hello")).isFalse();
    assertThat(RedisGlob.matches("a*b*c", "axxbyyc")).isTrue();
    assertThat(RedisGlob.matches("a*b*c", "axxbyy")).isFalse();
    // Regex metacharacters are literal
    assertThat(RedisGlob.matches("a.c", "abc")).isFalse();
    assertThat(RedisGlob.matches("a.c", "a.c")).isTrue();
  }

  @Test
  void globDoesNotBacktrackExponentially() {
    // A pattern that backtracks exponentially in a naive recursive matcher
    final String text = "a".repeat(5_000);
    final String pattern = "a*".repeat(50) + "b";
    assertThat(RedisGlob.matches(pattern, text)).isFalse();
  }

  @Test
  void anExpiredKeyIsAbsentAndItsTtlIsDropped() {
    final ConcurrentMap<String, Object> space = new ConcurrentHashMap<>();
    RedisKeyspace.put(space, "k", "v");
    assertThat(RedisKeyspace.expireAt(space, "k", System.currentTimeMillis() - 1)).isTrue();
    assertThat(RedisKeyspace.get(space, "k")).isNull();
    assertThat(space).doesNotContainKey("k");
    assertThat(RedisKeyspace.ttlMillis(space, "k")).isEqualTo(-2L);
  }

  @Test
  void anOverwriteDropsTheTtlAndRemoveForgetsIt() {
    final ConcurrentMap<String, Object> space = new ConcurrentHashMap<>();
    RedisKeyspace.put(space, "k", "v");
    RedisKeyspace.expireAt(space, "k", System.currentTimeMillis() + 60_000);
    assertThat(RedisKeyspace.ttlMillis(space, "k")).isPositive();
    RedisKeyspace.put(space, "k", "v2");
    assertThat(RedisKeyspace.ttlMillis(space, "k")).isEqualTo(-1L);

    RedisKeyspace.expireAt(space, "k", System.currentTimeMillis() + 60_000);
    RedisKeyspace.remove(space, "k");
    RedisKeyspace.put(space, "k", "v3");
    assertThat(RedisKeyspace.ttlMillis(space, "k")).isEqualTo(-1L);
  }

  @Test
  void forgettingASpaceDropsItsTtls() {
    final ConcurrentMap<String, Object> space = new ConcurrentHashMap<>();
    RedisKeyspace.put(space, "k", "v");
    RedisKeyspace.expireAt(space, "k", System.currentTimeMillis() + 60_000);
    assertThat(RedisKeyspace.tracksExpiriesOf(space)).isTrue();
    RedisKeyspace.forget(space);
    assertThat(RedisKeyspace.tracksExpiriesOf(space)).isFalse();
  }
}
