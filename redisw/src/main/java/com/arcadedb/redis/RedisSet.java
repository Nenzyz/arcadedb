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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The value of a Redis set key: the members in an array plus each member's position in it, so SPOP and SRANDMEMBER pick a random
 * member in O(1) (a {@code HashSet} can only reach the n-th member by iterating to it), and a removal swaps the last member into
 * the hole.
 * <p>
 * Not thread safe: like {@link RedisList}, only read or changed inside a {@code compute} on its key.
 */
final class RedisSet {
  private final Map<String, Integer> positions = new HashMap<>();
  private       String[]             members   = new String[8];

  int size() {
    return positions.size();
  }

  boolean contains(final String member) {
    return positions.containsKey(member);
  }

  boolean add(final String member) {
    if (positions.containsKey(member))
      return false;
    final int size = positions.size();
    if (size == members.length)
      members = Arrays.copyOf(members, size << 1);
    members[size] = member;
    positions.put(member, size);
    return true;
  }

  boolean remove(final String member) {
    final Integer position = positions.remove(member);
    if (position == null)
      return false;
    final int last = positions.size();
    if (position != last) {
      final String moved = members[last];
      members[position] = moved;
      positions.put(moved, position);
    }
    members[last] = null;
    return true;
  }

  /** A random member, null when the set is empty. */
  String random(final Random random) {
    final int size = positions.size();
    return size == 0 ? null : members[random.nextInt(size)];
  }

  /** The members, copied. */
  List<String> members() {
    return new ArrayList<>(Arrays.asList(members).subList(0, positions.size()));
  }

  @Override
  public String toString() {
    return members().toString();
  }
}
