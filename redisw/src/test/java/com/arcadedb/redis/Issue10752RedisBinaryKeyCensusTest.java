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

import com.arcadedb.GlobalConfiguration;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.args.ListPosition;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Issue #10752: on the RESP wire a KEY keeps its bytes. It used to be sanitized like the text arguments, each byte that is not
 * valid UTF-8 becoming U+FFFD, so two binary keys that differ only in such a byte were one key: a SET of one overwrote the other,
 * a GETDEL of one deleted both.
 * <p>
 * A census, not a per-command assertion: every key position {@link RedisArguments} lists is probed, in both key spaces (the
 * connection's own bucket and a database's), with two keys that differ only in an invalid byte, and must answer two keys. Writing
 * one binary key and reading it back is not enough: that passes with the keys collapsed, the collision needs two keys to show. A
 * key command added to the table without a probe here fails the census.
 *
 * @author Luca Garulli (l.garulli@arcadedata.com)
 */
public class Issue10752RedisBinaryKeyCensusTest extends BaseRedisServerTest {

  /** The commands whose key positions only exist in a database's key space (the transient form of the hash commands). */
  private static final List<String> DATABASE_ONLY = List.of("HGET", "HEXISTS", "HMGET", "HDEL");

  @Test
  void everyKeyPositionKeepsTwoBinaryKeysApart() {
    final List<String> probed = new ArrayList<>();
    try (final Jedis jedis = connect()) {
      for (final String command : new TreeSet<>(RedisArguments.keyCommands())) {
        if (!DATABASE_ONLY.contains(command)) {
          probe(jedis, command, "");
          probed.add(command + "@connection");
        }
        probe(jedis, command, getDatabaseName() + ".");
        probed.add(command + "@database");
      }
    }
    assertThat(probed).as("every key command of the table was probed").hasSizeGreaterThanOrEqualTo(RedisArguments.keyCommands().size());
  }

  @Test
  void theTableListsTheKeyOfEveryKeyValueCommand() {
    for (final String command : List.of("GET", "GETDEL", "EXISTS", "SET", "INCR", "INCRBY", "INCRBYFLOAT", "DECR", "DECRBY"))
      assertThat(RedisArguments.keyPositions(command)).as(command).isNotNull().startsWith(1);
    // the stored value of SET is opaque too (issue #9057), its options are text
    final int[] set = RedisArguments.opaquePositions("SET");
    assertThat(RedisArguments.isOpaque(set, 1)).isTrue();
    assertThat(RedisArguments.isOpaque(set, 2)).isTrue();
    assertThat(RedisArguments.isOpaque(set, 3)).isFalse();
    // an amount is parsed
    assertThat(RedisArguments.isOpaque(RedisArguments.opaquePositions("INCRBY"), 2)).isFalse();
    // the bucket of a hash command names a database, a type or an index
    assertThat(RedisArguments.isOpaque(RedisArguments.opaquePositions("HSET"), 1)).isFalse();
    assertThat(RedisArguments.isOpaque(RedisArguments.opaquePositions("HGET"), 1)).isFalse();
  }

  private void probe(final Jedis jedis, final String command, final String prefix) {
    final String tag = command.toLowerCase() + "-k";
    final byte[] a = key(prefix, tag, 0x80);
    final byte[] b = key(prefix, tag, 0x81);
    final String where = command + " in " + (prefix.isEmpty() ? "the connection's key space" : "the database's key space");
    final byte[] bucket = getDatabaseName().getBytes(StandardCharsets.US_ASCII);
    final byte[] fieldA = key("", tag, 0x80);
    final byte[] fieldB = key("", tag, 0x81);

    switch (command) {
    case "SET", "GET" -> {
      assertThat(jedis.set(a, bytes("A"))).isEqualTo("OK");
      assertThat(jedis.set(b, bytes("B"))).isEqualTo("OK");
      assertThat(jedis.get(a)).as(where).isEqualTo(bytes("A"));
      assertThat(jedis.get(b)).as(where).isEqualTo(bytes("B"));
    }
    case "GETDEL" -> {
      jedis.set(a, bytes("A"));
      jedis.set(b, bytes("B"));
      assertThat(jedis.getDel(a)).as(where).isEqualTo(bytes("A"));
      assertThat(jedis.exists(b)).as(where + ": the GETDEL of one key left the other").isTrue();
      assertThat(jedis.get(b)).as(where).isEqualTo(bytes("B"));
    }
    case "EXISTS" -> {
      jedis.set(a, bytes("A"));
      assertThat(jedis.exists(b)).as(where).isFalse();
      assertThat(jedis.exists(a, b)).as(where).isEqualTo(1L);
    }
    case "INCR" -> {
      assertThat(jedis.incr(a)).isEqualTo(1L);
      assertThat(jedis.incr(b)).as(where).isEqualTo(1L);
      assertThat(jedis.incr(a)).as(where).isEqualTo(2L);
    }
    case "INCRBY" -> {
      assertThat(jedis.incrBy(a, 5)).isEqualTo(5L);
      assertThat(jedis.incrBy(b, 7)).as(where).isEqualTo(7L);
    }
    case "INCRBYFLOAT" -> {
      assertThat(jedis.incrByFloat(a, 1.5)).isEqualTo(1.5);
      assertThat(jedis.incrByFloat(b, 2.5)).as(where).isEqualTo(2.5);
    }
    case "DECR" -> {
      assertThat(jedis.decr(a)).isEqualTo(-1L);
      assertThat(jedis.decr(b)).as(where).isEqualTo(-1L);
    }
    case "DECRBY" -> {
      assertThat(jedis.decrBy(a, 2)).isEqualTo(-2L);
      assertThat(jedis.decrBy(b, 3)).as(where).isEqualTo(-3L);
    }
    case "HGET" -> {
      jedis.set(a, bytes("A"));
      jedis.set(b, bytes("B"));
      assertThat(jedis.hget(bucket, fieldA)).as(where).isEqualTo(bytes("A"));
      assertThat(jedis.hget(bucket, fieldB)).as(where).isEqualTo(bytes("B"));
    }
    case "HEXISTS" -> {
      jedis.set(a, bytes("A"));
      assertThat(jedis.hexists(bucket, fieldA)).as(where).isTrue();
      assertThat(jedis.hexists(bucket, fieldB)).as(where).isFalse();
    }
    case "HMGET" -> {
      jedis.set(a, bytes("A"));
      jedis.set(b, bytes("B"));
      final List<byte[]> values = jedis.hmget(bucket, fieldA, fieldB);
      assertThat(values).hasSize(2);
      assertThat(values.get(0)).as(where).isEqualTo(bytes("A"));
      assertThat(values.get(1)).as(where).isEqualTo(bytes("B"));
    }
    case "HDEL" -> {
      jedis.set(a, bytes("A"));
      jedis.set(b, bytes("B"));
      assertThat(jedis.hdel(bucket, fieldA)).as(where).isEqualTo(1L);
      assertThat(jedis.exists(b)).as(where + ": the HDEL of one key left the other").isTrue();
    }
    case "DEL", "UNLINK" -> {
      jedis.set(a, bytes("A"));
      jedis.set(b, bytes("B"));
      assertThat("DEL".equals(command) ? jedis.del(a) : jedis.unlink(a)).as(where).isEqualTo(1L);
      assertThat(jedis.exists(b)).as(where + ": deleting one key deleted the other").isTrue();
    }
    case "TYPE" -> {
      jedis.set(a, bytes("A"));
      jedis.rpush(b, bytes("B"));
      assertThat(jedis.type(a)).as(where).isEqualTo("string");
      assertThat(jedis.type(b)).as(where).isEqualTo("list");
    }
    case "EXPIRE", "PEXPIRE", "EXPIREAT", "PEXPIREAT", "TTL", "PTTL", "PERSIST" -> {
      jedis.set(a, bytes("A"));
      jedis.set(b, bytes("B"));
      switch (command) {
      case "EXPIRE" -> jedis.expire(a, 100);
      case "PEXPIRE" -> jedis.pexpire(a, 100_000);
      case "EXPIREAT" -> jedis.expireAt(a, System.currentTimeMillis() / 1000 + 100);
      case "PEXPIREAT" -> jedis.pexpireAt(a, System.currentTimeMillis() + 100_000);
      default -> {
        jedis.expire(a, 100);
        jedis.expire(b, 100);
        if ("PERSIST".equals(command))
          assertThat(jedis.persist(a)).as(where).isEqualTo(1L);
      }
      }
      if ("PERSIST".equals(command)) {
        assertThat(jedis.ttl(a)).as(where).isEqualTo(-1L);
        assertThat(jedis.ttl(b)).as(where).isPositive();
      } else {
        assertThat("PTTL".equals(command) ? jedis.pttl(a) : jedis.ttl(a)).as(where).isPositive();
        if (!"TTL".equals(command) && !"PTTL".equals(command))
          assertThat(jedis.ttl(b)).as(where + ": the TTL of one key went to the other").isEqualTo(-1L);
      }
    }
    case "RENAME", "RENAMENX" -> {
      jedis.set(a, bytes("A"));
      if ("RENAME".equals(command))
        jedis.rename(a, b);
      else
        assertThat(jedis.renamenx(a, b)).as(where).isEqualTo(1L);
      assertThat(jedis.exists(a)).as(where).isFalse();
      assertThat(jedis.get(b)).as(where).isEqualTo(bytes("A"));
    }
    case "APPEND", "STRLEN" -> {
      jedis.append(a, bytes("AA"));
      jedis.append(b, bytes("B"));
      assertThat(jedis.strlen(a)).as(where).isEqualTo(2L);
      assertThat(jedis.strlen(b)).as(where).isEqualTo(1L);
    }
    case "GETRANGE", "SETRANGE" -> {
      jedis.set(a, bytes("AAAA"));
      jedis.set(b, bytes("BBBB"));
      jedis.setrange(a, 1, bytes("x"));
      assertThat(jedis.getrange(a, 0, -1)).as(where).isEqualTo(bytes("AxAA"));
      assertThat(jedis.getrange(b, 0, -1)).as(where).isEqualTo(bytes("BBBB"));
    }
    case "MGET", "MSET", "MSETNX" -> {
      if ("MSETNX".equals(command))
        assertThat(jedis.msetnx(a, bytes("A"), b, bytes("B"))).as(where).isEqualTo(1L);
      else
        jedis.mset(a, bytes("A"), b, bytes("B"));
      final List<byte[]> values = jedis.mget(a, b);
      assertThat(values.get(0)).as(where).isEqualTo(bytes("A"));
      assertThat(values.get(1)).as(where).isEqualTo(bytes("B"));
    }
    case "GETSET", "SETNX" -> {
      jedis.set(a, bytes("A"));
      if ("GETSET".equals(command))
        assertThat(jedis.getSet(b, bytes("B"))).as(where).isNull();
      else
        assertThat(jedis.setnx(b, bytes("B"))).as(where).isEqualTo(1L);
      assertThat(jedis.get(a)).as(where).isEqualTo(bytes("A"));
      assertThat(jedis.get(b)).as(where).isEqualTo(bytes("B"));
    }
    case "SETEX", "PSETEX" -> {
      if ("SETEX".equals(command)) {
        jedis.setex(a, 100, bytes("A"));
        jedis.setex(b, 100, bytes("B"));
      } else {
        jedis.psetex(a, 100_000, bytes("A"));
        jedis.psetex(b, 100_000, bytes("B"));
      }
      assertThat(jedis.get(a)).as(where).isEqualTo(bytes("A"));
      assertThat(jedis.get(b)).as(where).isEqualTo(bytes("B"));
    }
    case "LPUSH", "RPUSH", "LPOP", "RPOP", "LLEN", "LRANGE", "LINDEX", "LSET", "LINSERT", "LREM", "LTRIM" -> {
      jedis.rpush(a, bytes("A1"), bytes("A2"));
      if ("LPUSH".equals(command))
        jedis.lpush(b, bytes("B"));
      else
        jedis.rpush(b, bytes("B"));
      switch (command) {
      case "LPOP" -> assertThat(jedis.lpop(a)).as(where).isEqualTo(bytes("A1"));
      case "RPOP" -> assertThat(jedis.rpop(a)).as(where).isEqualTo(bytes("A2"));
      case "LSET" -> jedis.lset(a, 0, bytes("A0"));
      case "LINSERT" -> jedis.linsert(a, ListPosition.BEFORE, bytes("A1"), bytes("A0"));
      case "LREM" -> assertThat(jedis.lrem(a, 0, bytes("A1"))).as(where).isEqualTo(1L);
      case "LTRIM" -> jedis.ltrim(a, 0, 0);
      default -> {
      }
      }
      assertThat(jedis.lrange(b, 0, -1)).as(where).containsExactly(bytes("B"));
      assertThat(jedis.llen(b)).as(where).isEqualTo(1L);
      assertThat(jedis.lindex(b, 0)).as(where).isEqualTo(bytes("B"));
      assertThat(jedis.llen(a)).as(where).isPositive();
    }
    case "RPOPLPUSH" -> {
      jedis.rpush(a, bytes("A"));
      assertThat(jedis.rpoplpush(a, b)).as(where).isEqualTo(bytes("A"));
      assertThat(jedis.exists(a)).as(where).isFalse();
      assertThat(jedis.lrange(b, 0, -1)).as(where).containsExactly(bytes("A"));
    }
    case "SADD", "SREM", "SISMEMBER", "SMEMBERS", "SCARD", "SRANDMEMBER", "SPOP" -> {
      jedis.sadd(a, bytes("A1"), bytes("A2"));
      jedis.sadd(b, bytes("B"));
      switch (command) {
      case "SREM" -> assertThat(jedis.srem(a, bytes("A1"))).as(where).isEqualTo(1L);
      case "SPOP" -> assertThat(jedis.spop(a)).as(where).isNotNull();
      case "SRANDMEMBER" -> assertThat(jedis.srandmember(b)).as(where).isEqualTo(bytes("B"));
      default -> {
      }
      }
      assertThat(jedis.smembers(b)).as(where).containsExactly(bytes("B"));
      assertThat(jedis.scard(b)).as(where).isEqualTo(1L);
      assertThat(jedis.sismember(b, bytes("B"))).as(where).isTrue();
      assertThat(jedis.sismember(a, bytes("B"))).as(where).isFalse();
    }
    case "SDIFF", "SINTER", "SUNION" -> {
      jedis.sadd(a, bytes("X"), bytes("A"));
      jedis.sadd(b, bytes("X"), bytes("B"));
      final Set<byte[]> result = switch (command) {
        case "SDIFF" -> jedis.sdiff(a, b);
        case "SINTER" -> jedis.sinter(a, b);
        default -> jedis.sunion(a, b);
      };
      assertThat(result).as(where).hasSize("SUNION".equals(command) ? 3 : 1);
    }
    case "SDIFFSTORE", "SINTERSTORE", "SUNIONSTORE" -> {
      jedis.sadd(b, bytes("B"));
      final long stored = switch (command) {
        case "SDIFFSTORE" -> jedis.sdiffstore(a, b);
        case "SINTERSTORE" -> jedis.sinterstore(a, b, b);
        default -> jedis.sunionstore(a, b);
      };
      assertThat(stored).as(where).isEqualTo(1L);
      assertThat(jedis.smembers(a)).as(where).containsExactly(bytes("B"));
      assertThat(jedis.smembers(b)).as(where).containsExactly(bytes("B"));
    }
    default -> fail("RedisArguments lists '" + command + "' as a key command and this census has no probe for it: add one");
    }

    // nothing of this probe is left for the next one
    jedis.del(a, b);
  }

  private static byte[] key(final String prefix, final String tag, final int invalidByte) {
    final byte[] text = (prefix + tag).getBytes(StandardCharsets.US_ASCII);
    final byte[] key = Arrays.copyOf(text, text.length + 1);
    key[text.length] = (byte) invalidByte;
    return key;
  }

  private static byte[] bytes(final String text) {
    return text.getBytes(StandardCharsets.US_ASCII);
  }

  private Jedis connect() {
    final Jedis jedis = new Jedis("localhost", getServerRedisPort());
    jedis.auth("root", DEFAULT_PASSWORD_FOR_TESTS);
    return jedis;
  }

  @Override
  public void setTestConfiguration() {
    super.setTestConfiguration();
    GlobalConfiguration.SERVER_PLUGINS.setValue("Redis Protocol:com.arcadedb.redis.RedisProtocolPlugin");
  }
}
