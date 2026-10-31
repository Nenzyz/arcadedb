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

import com.arcadedb.log.LogManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import java.util.logging.Level;

/**
 * The operations on a Redis key space - a connection's own bucket or a database's global variables - that honour key expiry.
 * The RESP wire path and the {@code redis} query language both go through here, so a key one of them gave a TTL is gone for
 * the other too once it expires.
 * <p>
 * A TTL is kept apart from the value, in a map per key space, so a value stays what SQL reads as {@code $key}. Every change to
 * the TTL of a key happens inside a {@code compute} on that key in its key space, so it is serialized with every write of the
 * value: a SET that drops a TTL cannot interleave with an expiry that deletes the key, and a reader that finds the key expired
 * deletes it under the same lock, after checking again. An expired key is deleted when it is next touched, and a daemon thread
 * deletes the ones nobody touches, once a second.
 * <p>
 * TTLs are held in memory only: like the values of the key spaces, they are not persisted and not replicated.
 */
public final class RedisKeyspace {
  /** The TTLs (epoch millis) of each key space that has any, by the identity of its map. */
  private static final ConcurrentHashMap<Space, ConcurrentHashMap<String, Long>> EXPIRIES = new ConcurrentHashMap<>();

  private static final AtomicBoolean SWEEPER_STARTED = new AtomicBoolean();

  private RedisKeyspace() {
  }

  /** The live value of {@code key}, null when absent or expired. */
  public static Object get(final ConcurrentMap<String, Object> space, final String key) {
    final Object value = space.get(key);
    if (value == null || EXPIRIES.isEmpty())
      return value;
    final ConcurrentHashMap<String, Long> expiries = EXPIRIES.get(new Space(space));
    if (expiries == null)
      return value;
    final Long expiresAt = expiries.get(key);
    if (expiresAt == null || expiresAt > System.currentTimeMillis())
      return value;
    // Expired: delete it, checking again under the key's lock, so a value written since this read is not lost
    return space.computeIfPresent(key, (k, current) -> expired(space, k) ? null : current);
  }

  public static boolean containsKey(final ConcurrentMap<String, Object> space, final String key) {
    return get(space, key) != null;
  }

  /**
   * Writes {@code value} and drops the key's TTL, as a plain SET does.
   *
   * @return the previous live value, null if none
   */
  public static Object put(final ConcurrentMap<String, Object> space, final String key, final Object value) {
    final Object[] previous = new Object[1];
    space.compute(key, (k, current) -> {
      previous[0] = current != null && expired(space, k) ? null : current;
      clearTtl(space, k);
      return value;
    });
    return previous[0];
  }

  /**
   * Writes {@code value} only when the key is absent (or expired).
   *
   * @return the live value that vetoed the write, null when written
   */
  public static Object putIfAbsent(final ConcurrentMap<String, Object> space, final String key, final Object value) {
    final Object[] existing = new Object[1];
    space.compute(key, (k, current) -> {
      if (current != null && !expired(space, k)) {
        existing[0] = current;
        return current;
      }
      clearTtl(space, k);
      return value;
    });
    return existing[0];
  }

  /**
   * Writes {@code value} only when the key is present (and not expired).
   *
   * @param keepTtl whether the key keeps its TTL, as SET KEEPTTL does
   *
   * @return the previous live value, null when the key was absent and nothing was written
   */
  public static Object putIfPresent(final ConcurrentMap<String, Object> space, final String key, final Object value,
      final boolean keepTtl) {
    final Object[] previous = new Object[1];
    space.computeIfPresent(key, (k, current) -> {
      if (expired(space, k))
        return null;
      previous[0] = current;
      if (!keepTtl)
        clearTtl(space, k);
      return value;
    });
    return previous[0];
  }

  /**
   * Atomic read-modify-write: {@code remapping} sees the live value (null when absent or expired) and its result is stored. The
   * key keeps its TTL, as INCR and the list and set commands keep it, unless {@code remapping} answers null, which deletes the key.
   *
   * @return the value {@code remapping} answered
   */
  public static Object compute(final ConcurrentMap<String, Object> space, final String key, final UnaryOperator<Object> remapping) {
    return space.compute(key, (k, current) -> {
      if (current != null && expired(space, k))
        current = null;
      final Object result = remapping.apply(current);
      if (result == null)
        clearTtl(space, k);
      return result;
    });
  }

  /** Deletes the key and its TTL. @return the previous live value, null if none */
  public static Object remove(final ConcurrentMap<String, Object> space, final String key) {
    final Object[] previous = new Object[1];
    space.computeIfPresent(key, (k, current) -> {
      previous[0] = expired(space, k) ? null : current;
      clearTtl(space, k);
      return null;
    });
    return previous[0];
  }

  /**
   * Sets the TTL of an existing key to {@code expiresAt} (epoch millis); a time already past deletes the key, as in Redis.
   *
   * @return true when the key existed
   */
  public static boolean expireAt(final ConcurrentMap<String, Object> space, final String key, final long expiresAt) {
    final boolean[] existed = new boolean[1];
    space.computeIfPresent(key, (k, current) -> {
      if (expired(space, k))
        return null;
      existed[0] = true;
      if (expiresAt <= System.currentTimeMillis()) {
        clearTtl(space, k);
        return null;
      }
      setTtl(space, k, expiresAt);
      return current;
    });
    return existed[0];
  }

  /** Writes {@code value} with a TTL, as SETEX and SET EX do. */
  public static void putWithTtl(final ConcurrentMap<String, Object> space, final String key, final Object value,
      final long expiresAt) {
    space.compute(key, (k, current) -> {
      setTtl(space, k, expiresAt);
      return value;
    });
  }

  /** Drops the TTL of the key. @return true when the key had one */
  public static boolean persist(final ConcurrentMap<String, Object> space, final String key) {
    final boolean[] had = new boolean[1];
    space.computeIfPresent(key, (k, current) -> {
      if (expired(space, k))
        return null;
      had[0] = clearTtl(space, k) != null;
      return current;
    });
    return had[0];
  }

  /** The milliseconds the key has left: -2 when absent, -1 when it has no TTL. */
  public static long ttlMillis(final ConcurrentMap<String, Object> space, final String key) {
    if (get(space, key) == null)
      return -2L;
    final ConcurrentHashMap<String, Long> expiries = EXPIRIES.isEmpty() ? null : EXPIRIES.get(new Space(space));
    final Long expiresAt = expiries != null ? expiries.get(key) : null;
    return expiresAt == null ? -1L : Math.max(0L, expiresAt - System.currentTimeMillis());
  }

  /** The TTL of the key as epoch millis, null when it has none. For a RENAME, which moves it with the value. */
  static Long expiresAt(final ConcurrentMap<String, Object> space, final String key) {
    final ConcurrentHashMap<String, Long> expiries = EXPIRIES.isEmpty() ? null : EXPIRIES.get(new Space(space));
    return expiries != null ? expiries.get(key) : null;
  }

  /** The live keys, deleting the expired ones met on the way. */
  public static List<String> keys(final ConcurrentMap<String, Object> space) {
    final List<String> keys = new ArrayList<>(space.size());
    for (final String key : space.keySet())
      if (get(space, key) != null)
        keys.add(key);
    return keys;
  }

  /** Empties the key space and forgets its TTLs. */
  public static void clear(final ConcurrentMap<String, Object> space) {
    for (final String key : space.keySet())
      remove(space, key);
    forget(space);
  }

  /** Forgets the TTLs of a key space that goes away, a connection's bucket when the connection closes. */
  public static void forget(final ConcurrentMap<String, Object> space) {
    EXPIRIES.remove(new Space(space));
  }

  static boolean tracksExpiriesOf(final ConcurrentMap<String, Object> space) {
    return EXPIRIES.containsKey(new Space(space));
  }

  // --- CALLED ONLY INSIDE A compute ON THE KEY, SEE THE CLASS DOC ---

  private static boolean expired(final ConcurrentMap<String, Object> space, final String key) {
    if (EXPIRIES.isEmpty())
      return false;
    final ConcurrentHashMap<String, Long> expiries = EXPIRIES.get(new Space(space));
    if (expiries == null)
      return false;
    final Long expiresAt = expiries.get(key);
    if (expiresAt == null || expiresAt > System.currentTimeMillis())
      return false;
    expiries.remove(key);
    return true;
  }

  private static Long clearTtl(final ConcurrentMap<String, Object> space, final String key) {
    if (EXPIRIES.isEmpty())
      return null;
    final ConcurrentHashMap<String, Long> expiries = EXPIRIES.get(new Space(space));
    return expiries != null ? expiries.remove(key) : null;
  }

  private static void setTtl(final ConcurrentMap<String, Object> space, final String key, final long expiresAt) {
    // compute, not computeIfAbsent + put: the sweeper drops an empty map with a computeIfPresent, and a TTL put into a map it
    // dropped in between would be lost
    EXPIRIES.compute(new Space(space), (s, expiries) -> {
      if (expiries == null)
        expiries = new ConcurrentHashMap<>();
      expiries.put(key, expiresAt);
      return expiries;
    });
    startSweeper();
  }

  private static void startSweeper() {
    if (SWEEPER_STARTED.get() || !SWEEPER_STARTED.compareAndSet(false, true))
      return;
    final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
      final Thread thread = new Thread(r, "ArcadeDB-Redis-Expiry");
      thread.setDaemon(true);
      return thread;
    });
    sweeper.scheduleWithFixedDelay(RedisKeyspace::sweep, 1, 1, TimeUnit.SECONDS);
  }

  /** Deletes the keys whose TTL passed and nobody touched since. */
  static void sweep() {
    try {
      final long now = System.currentTimeMillis();
      for (final Map.Entry<Space, ConcurrentHashMap<String, Long>> entry : EXPIRIES.entrySet()) {
        final ConcurrentMap<String, Object> space = entry.getKey().map;
        for (final Map.Entry<String, Long> ttl : entry.getValue().entrySet()) {
          final String key = ttl.getKey();
          if (ttl.getValue() <= now)
            space.computeIfPresent(key, (k, current) -> expired(space, k) ? null : current);
          else if (!space.containsKey(key))
            // A TTL left for a key deleted outside these methods (SQL, a database restore) is dropped, under the key's lock: a
            // SETEX in progress has set its TTL and not yet published its value
            space.compute(key, (k, current) -> {
              if (current == null)
                clearTtl(space, k);
              return current;
            });
        }
        EXPIRIES.computeIfPresent(entry.getKey(), (s, expiries) -> expiries.isEmpty() ? null : expiries);
      }
    } catch (final Throwable t) {
      // the sweeper must survive whatever one key space does, or no TTL would expire unattended again
      LogManager.instance().log(RedisKeyspace.class, Level.WARNING, "Redis wrapper: error expiring keys", t);
    }
  }

  /** A key space by identity: a map's own equals and hashCode follow its content. */
  private record Space(ConcurrentMap<String, Object> map) {
    @Override
    public boolean equals(final Object o) {
      return o instanceof Space other && other.map == map;
    }

    @Override
    public int hashCode() {
      return System.identityHashCode(map);
    }
  }
}
