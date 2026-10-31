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
import java.util.List;

/**
 * The value of a Redis list key: a ring buffer over a plain array, so a push or a pop at either end is O(1) (an LPUSH on an
 * {@code ArrayList} shifts every element) and an index is O(1) too (LINDEX, LSET, LRANGE).
 * <p>
 * Not thread safe: it lives in a key space map and is only read or changed inside a {@code compute} on its key, which
 * serializes every access to it (see {@link RedisKeyspace#compute}).
 */
final class RedisList {
  private String[] elements = new String[8];
  private int      head     = 0;
  private int      size     = 0;

  int size() {
    return size;
  }

  String get(final int index) {
    return elements[slot(index)];
  }

  void set(final int index, final String element) {
    elements[slot(index)] = element;
  }

  void addFirst(final String element) {
    ensureCapacity();
    head = (head - 1) & (elements.length - 1);
    elements[head] = element;
    size++;
  }

  void addLast(final String element) {
    ensureCapacity();
    elements[(head + size) & (elements.length - 1)] = element;
    size++;
  }

  String pollFirst() {
    if (size == 0)
      return null;
    final String element = elements[head];
    elements[head] = null;
    head = (head + 1) & (elements.length - 1);
    size--;
    return element;
  }

  String pollLast() {
    if (size == 0)
      return null;
    final int last = slot(size - 1);
    final String element = elements[last];
    elements[last] = null;
    size--;
    return element;
  }

  /** Inserts {@code element} so that it ends up at {@code index}, shifting the elements from there on by one. */
  void insert(final int index, final String element) {
    if (index == 0) {
      addFirst(element);
      return;
    }
    if (index == size) {
      addLast(element);
      return;
    }
    ensureCapacity();
    for (int i = size; i > index; i--)
      elements[slot(i)] = elements[slot(i - 1)];
    elements[slot(index)] = element;
    size++;
  }

  String remove(final int index) {
    if (index == 0)
      return pollFirst();
    final String element = get(index);
    for (int i = index; i < size - 1; i++)
      elements[slot(i)] = elements[slot(i + 1)];
    elements[slot(size - 1)] = null;
    size--;
    return element;
  }

  int indexOf(final String element) {
    for (int i = 0; i < size; i++)
      if (element.equals(get(i)))
        return i;
    return -1;
  }

  /** The elements from {@code from} (inclusive) to {@code to} (exclusive), copied. */
  List<String> range(final int from, final int to) {
    final List<String> result = new ArrayList<>(Math.max(0, to - from));
    for (int i = from; i < to; i++)
      result.add(get(i));
    return result;
  }

  /** Keeps the elements from {@code start} to {@code stop}, both inclusive and within bounds. */
  void trim(final int start, final int stop) {
    final String[] kept = new String[capacityFor(stop - start + 1)];
    for (int i = start; i <= stop; i++)
      kept[i - start] = get(i);
    elements = kept;
    head = 0;
    size = stop - start + 1;
  }

  @Override
  public String toString() {
    return range(0, size).toString();
  }

  private int slot(final int index) {
    return (head + index) & (elements.length - 1);
  }

  private void ensureCapacity() {
    if (size < elements.length)
      return;
    final String[] grown = new String[elements.length << 1];
    for (int i = 0; i < size; i++)
      grown[i] = get(i);
    elements = grown;
    head = 0;
  }

  private static int capacityFor(final int count) {
    int capacity = 8;
    while (capacity < count)
      capacity <<= 1;
    return capacity;
  }
}
