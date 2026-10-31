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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.args.ListPosition;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.params.SetParams;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The key, string, list, set, expiry and connection commands of the RESP wire path, on each keyspace a key can resolve to: the
 * connection's own bucket, a database's global variables by prefix, and the database a SELECT made current.
 */
public class RedisKeyspaceCommandsTest extends BaseRedisServerTest {

  @Test
  void keyManagementCommands() {
    try (final Jedis jedis = connect()) {
      jedis.set("km:a", "1");
      jedis.set("km:b", "2");

      assertThat(jedis.type("km:a")).isEqualTo("string");
      assertThat(jedis.type("km:doesNotExist")).isEqualTo("none");

      assertThat(jedis.del("km:a", "km:b", "km:doesNotExist")).isEqualTo(2);
      assertThat(jedis.exists("km:a")).isFalse();

      jedis.set("km:old", "value");
      jedis.rename("km:old", "km:new");
      assertThat(jedis.exists("km:old")).isFalse();
      assertThat(jedis.get("km:new")).isEqualTo("value");
      assertThatThrownBy(() -> jedis.rename("km:doesNotExist", "km:whatever")).isInstanceOf(JedisDataException.class)
          .hasMessageContaining("no such key");

      jedis.set("km:src", "srcValue");
      jedis.set("km:dst", "dstValue");
      assertThat(jedis.renamenx("km:src", "km:dst")).isEqualTo(0L);
      assertThat(jedis.get("km:dst")).isEqualTo("dstValue");
      jedis.del("km:dst");
      assertThat(jedis.renamenx("km:src", "km:dst")).isEqualTo(1L);
      assertThat(jedis.get("km:dst")).isEqualTo("srcValue");
    }
  }

  @Test
  void renameAcrossKeyspacesIsRefused() {
    try (final Jedis jedis = connect()) {
      jedis.set("local", "value");
      assertThatThrownBy(() -> jedis.rename("local", getDatabaseName() + ".remote")).isInstanceOf(JedisDataException.class);
      assertThat(jedis.get("local")).isEqualTo("value");
    }
  }

  @Test
  void expiryOnTheConnectionBucket() throws InterruptedException {
    try (final Jedis jedis = connect()) {
      assertExpiryCommands(jedis, "");
    }
  }

  @Test
  void expiryOnADatabaseKeyByPrefix() throws InterruptedException {
    try (final Jedis jedis = connect()) {
      assertExpiryCommands(jedis, getDatabaseName() + ".");
    }
  }

  @Test
  void expiryOnTheSelectedDatabase() throws InterruptedException {
    try (final Jedis jedis = connect()) {
      jedis.sendCommand(Protocol.Command.SELECT, getDatabaseName());
      assertExpiryCommands(jedis, "");
    }
  }

  private void assertExpiryCommands(final Jedis jedis, final String prefix) throws InterruptedException {
    jedis.set(prefix + "exp:noTtl", "value");
    assertThat(jedis.ttl(prefix + "exp:noTtl")).isEqualTo(-1L);
    assertThat(jedis.ttl(prefix + "exp:missing")).isEqualTo(-2L);
    assertThat(jedis.expire(prefix + "exp:missing", 10)).isEqualTo(0L);

    jedis.set(prefix + "exp:withTtl", "value");
    assertThat(jedis.expire(prefix + "exp:withTtl", 100)).isEqualTo(1L);
    assertThat(jedis.ttl(prefix + "exp:withTtl")).isBetween(1L, 100L);
    assertThat(jedis.pttl(prefix + "exp:withTtl")).isBetween(1L, 100_000L);

    assertThat(jedis.persist(prefix + "exp:withTtl")).isEqualTo(1L);
    assertThat(jedis.ttl(prefix + "exp:withTtl")).isEqualTo(-1L);
    assertThat(jedis.persist(prefix + "exp:withTtl")).isEqualTo(0L);

    // A plain SET drops the TTL, KEEPTTL keeps it
    jedis.set(prefix + "exp:reset", "value", SetParams.setParams().ex(100));
    assertThat(jedis.ttl(prefix + "exp:reset")).isBetween(1L, 100L);
    jedis.set(prefix + "exp:reset", "value2", SetParams.setParams().keepTtl());
    assertThat(jedis.ttl(prefix + "exp:reset")).isBetween(1L, 100L);
    jedis.set(prefix + "exp:reset", "value3");
    assertThat(jedis.ttl(prefix + "exp:reset")).isEqualTo(-1L);

    // INCR keeps the TTL, like Redis
    jedis.set(prefix + "exp:counter", "1", SetParams.setParams().ex(100));
    jedis.incr(prefix + "exp:counter");
    assertThat(jedis.ttl(prefix + "exp:counter")).isBetween(1L, 100L);

    // Keys actually expire: SET PX, SETEX/PSETEX, PEXPIRE
    jedis.set(prefix + "exp:px", "value", SetParams.setParams().px(100));
    jedis.psetex(prefix + "exp:psetex", 100, "value");
    jedis.set(prefix + "exp:pexpire", "value");
    assertThat(jedis.pexpire(prefix + "exp:pexpire", 100)).isEqualTo(1L);
    jedis.rpush(prefix + "exp:list", "a");
    jedis.pexpire(prefix + "exp:list", 100);
    Thread.sleep(400);
    for (final String key : new String[] { "exp:px", "exp:psetex", "exp:pexpire", "exp:list" }) {
      assertThat(jedis.exists(prefix + key)).as(key).isFalse();
      assertThat(jedis.get(prefix + "exp:px")).isNull();
      assertThat(jedis.ttl(prefix + key)).as(key).isEqualTo(-2L);
      assertThat(jedis.type(prefix + key)).as(key).isEqualTo("none");
    }

    // An expired key is absent for NX
    jedis.set(prefix + "exp:nx", "old", SetParams.setParams().px(50));
    Thread.sleep(200);
    assertThat(jedis.set(prefix + "exp:nx", "new", SetParams.setParams().nx())).isEqualTo("OK");
    assertThat(jedis.get(prefix + "exp:nx")).isEqualTo("new");
    assertThat(jedis.ttl(prefix + "exp:nx")).isEqualTo(-1L);

    jedis.setex(prefix + "exp:setex", 100, "value");
    assertThat(jedis.get(prefix + "exp:setex")).isEqualTo("value");
    assertThat(jedis.ttl(prefix + "exp:setex")).isBetween(1L, 100L);
    assertThatThrownBy(() -> jedis.setex(prefix + "exp:bad", 0, "value")).isInstanceOf(JedisDataException.class);
    assertThatThrownBy(() -> jedis.set(prefix + "exp:bad", "value", SetParams.setParams().ex(-1))).isInstanceOf(
        JedisDataException.class);
  }

  @Test
  void theQueryLanguageSeesAnExpiredKeyAsAbsent() throws Exception {
    try (final Jedis jedis = connect()) {
      jedis.set(getDatabaseName() + ".ql:expiring", "value", SetParams.setParams().px(100));
      assertThat(getResultValue(executeQuery(0, "redis", "GET ql:expiring"))).isEqualTo("value");
      Thread.sleep(400);
      assertThat(getResultValue(executeQuery(0, "redis", "GET ql:expiring"))).isNull();
      assertThat(getResultValueAsInt(executeQuery(0, "redis", "EXISTS ql:expiring"))).isZero();
    }
  }

  @Test
  void theConnectionBucketStaysPrivateToItsConnection() {
    try (final Jedis first = connect(); final Jedis second = connect()) {
      first.set("private:key", "first");
      first.rpush("private:list", "a");
      assertThat(second.exists("private:key")).isFalse();
      assertThat(second.keys("private:*")).isEmpty();
      assertThat(second.dbSize()).isZero();
    }
  }

  @Test
  void keysRandomKeyDbSizeAndFlushDb() {
    try (final Jedis jedis = connect()) {
      jedis.set("glob:apple", "1");
      jedis.set("glob:apricot", "2");
      jedis.set("glob:banana", "3");
      jedis.sadd("glob:set", "x");

      assertThat(jedis.keys("glob:ap*")).containsExactlyInAnyOrder("glob:apple", "glob:apricot");
      assertThat(jedis.keys("glob:[ab]*n*")).containsExactlyInAnyOrder("glob:banana");
      assertThat(jedis.keys("glob:?pple")).containsExactlyInAnyOrder("glob:apple");
      assertThat(jedis.keys("*")).hasSize(4);
      assertThat(jedis.randomKey()).startsWith("glob:");
      assertThat(jedis.dbSize()).isEqualTo(4L);

      jedis.flushDB();
      assertThat(jedis.dbSize()).isZero();
      assertThat(jedis.randomKey()).isNull();
      assertThat(jedis.exists("glob:apple")).isFalse();
    }
  }

  @Test
  void keysOnASelectedDatabaseListItsGlobalVariables() {
    try (final Jedis jedis = connect()) {
      jedis.set(getDatabaseName() + ".dbkeys:one", "1");
      jedis.set("dbkeys:local", "2");
      jedis.sendCommand(Protocol.Command.SELECT, getDatabaseName());
      assertThat(jedis.keys("dbkeys:*")).containsExactly("dbkeys:one");
    }
  }

  @Test
  void stringCommands() {
    try (final Jedis jedis = connect()) {
      assertThat(jedis.append("str:greeting", "Hello")).isEqualTo(5L);
      assertThat(jedis.append("str:greeting", " World")).isEqualTo(11L);
      assertThat(jedis.strlen("str:greeting")).isEqualTo(11L);
      assertThat(jedis.strlen("str:missing")).isZero();
      assertThat(jedis.get("str:greeting")).isEqualTo("Hello World");

      assertThat(jedis.getrange("str:greeting", 0, 4)).isEqualTo("Hello");
      assertThat(jedis.getrange("str:greeting", -5, -1)).isEqualTo("World");
      assertThat(jedis.getrange("str:greeting", 20, 30)).isEmpty();
      assertThat(jedis.setrange("str:greeting", 6, "Redis")).isEqualTo(11L);
      assertThat(jedis.get("str:greeting")).isEqualTo("Hello Redis");
      assertThat(jedis.setrange("str:padded", 2, "x")).isEqualTo(3L);
      assertThat(jedis.get("str:padded")).isEqualTo("\0\0x");

      // Byte counts, not char counts
      jedis.set("str:utf8", "é");
      assertThat(jedis.strlen("str:utf8")).isEqualTo(2L);
      assertThat(jedis.append("str:utf8", "a")).isEqualTo(3L);

      jedis.mset("str:m1", "one", "str:m2", "two");
      assertThat(jedis.mget("str:m1", "str:m2", "str:missing")).containsExactly("one", "two", null);
      assertThat(jedis.msetnx("str:m1", "clobbered", "str:m3", "three")).isEqualTo(0L);
      assertThat(jedis.get("str:m1")).isEqualTo("one");
      assertThat(jedis.exists("str:m3")).isFalse();
      assertThat(jedis.msetnx("str:m3", "three", "str:m4", "four")).isEqualTo(1L);
      assertThat(jedis.get("str:m4")).isEqualTo("four");
      assertThatThrownBy(() -> jedis.sendCommand(Protocol.Command.MSET, "str:odd")).isInstanceOf(JedisDataException.class);

      assertThat(jedis.getSet("str:gs", "first")).isNull();
      assertThat(jedis.getSet("str:gs", "second")).isEqualTo("first");
      assertThat(jedis.setnx("str:gs", "third")).isEqualTo(0L);
      assertThat(jedis.get("str:gs")).isEqualTo("second");
      assertThat(jedis.setnx("str:new", "value")).isEqualTo(1L);

      assertThat(jedis.echo("echoed")).isEqualTo("echoed");
    }
  }

  @Test
  void listCommands() {
    try (final Jedis jedis = connect()) {
      assertListCommands(jedis, "");
    }
  }

  @Test
  void listCommandsOnADatabaseKey() {
    try (final Jedis jedis = connect()) {
      assertListCommands(jedis, getDatabaseName() + ".");
    }
  }

  private void assertListCommands(final Jedis jedis, final String prefix) {
    final String l = prefix + "list:l";
    assertThat(jedis.rpush(l, "a", "b", "c")).isEqualTo(3L);
    assertThat(jedis.lpush(l, "z")).isEqualTo(4L);
    assertThat(jedis.type(l)).isEqualTo("list");
    assertThat(jedis.lrange(l, 0, -1)).containsExactly("z", "a", "b", "c");
    assertThat(jedis.lrange(l, -2, 100)).containsExactly("b", "c");
    assertThat(jedis.lrange(l, 3, 1)).isEmpty();
    assertThat(jedis.llen(l)).isEqualTo(4L);

    assertThat(jedis.lindex(l, 0)).isEqualTo("z");
    assertThat(jedis.lindex(l, -1)).isEqualTo("c");
    assertThat(jedis.lindex(l, 10)).isNull();

    assertThat(jedis.lset(l, 0, "zz")).isEqualTo("OK");
    assertThat(jedis.lindex(l, 0)).isEqualTo("zz");
    assertThatThrownBy(() -> jedis.lset(l, 10, "x")).isInstanceOf(JedisDataException.class);

    assertThat(jedis.linsert(l, ListPosition.AFTER, "zz", "yy")).isEqualTo(5L);
    assertThat(jedis.linsert(l, ListPosition.BEFORE, "zz", "xx")).isEqualTo(6L);
    assertThat(jedis.linsert(l, ListPosition.BEFORE, "nope", "xx")).isEqualTo(-1L);
    assertThat(jedis.lrange(l, 0, -1)).containsExactly("xx", "zz", "yy", "a", "b", "c");

    assertThat(jedis.lrem(l, 0, "yy")).isEqualTo(1L);
    assertThat(jedis.lrem(l, 0, "xx")).isEqualTo(1L);
    assertThat(jedis.lpop(l)).isEqualTo("zz");
    assertThat(jedis.rpop(l)).isEqualTo("c");
    assertThat(jedis.lrange(l, 0, -1)).containsExactly("a", "b");

    jedis.rpush(l, "a", "b", "a");
    assertThat(jedis.lrem(l, -2, "a")).isEqualTo(2L);
    assertThat(jedis.lrange(l, 0, -1)).containsExactly("a", "b", "b");
    assertThat(jedis.lrem(l, 1, "b")).isEqualTo(1L);
    assertThat(jedis.lrange(l, 0, -1)).containsExactly("a", "b");

    assertThat(jedis.ltrim(l, 0, 0)).isEqualTo("OK");
    assertThat(jedis.lrange(l, 0, -1)).containsExactly("a");

    jedis.rpush(prefix + "list:src", "x", "y");
    assertThat(jedis.rpoplpush(prefix + "list:src", prefix + "list:dst")).isEqualTo("y");
    assertThat(jedis.lrange(prefix + "list:dst", 0, -1)).containsExactly("y");
    assertThat(jedis.rpoplpush(prefix + "list:missing", prefix + "list:dst")).isNull();

    // Popping the last element removes the key
    jedis.rpush(prefix + "list:solo", "only");
    jedis.lpop(prefix + "list:solo");
    assertThat(jedis.exists(prefix + "list:solo")).isFalse();
    assertThat(jedis.type(prefix + "list:solo")).isEqualTo("none");
    assertThat(jedis.lpop(prefix + "list:solo")).isNull();
    assertThat(jedis.llen(prefix + "list:solo")).isZero();

    // WRONGTYPE both ways
    jedis.set(prefix + "list:string", "plain");
    assertThatThrownBy(() -> jedis.lpush(prefix + "list:string", "x")).isInstanceOf(JedisDataException.class)
        .hasMessageStartingWith("WRONGTYPE");
    assertThatThrownBy(() -> jedis.get(l)).isInstanceOf(JedisDataException.class).hasMessageStartingWith("WRONGTYPE");
    assertThatThrownBy(() -> jedis.append(l, "x")).isInstanceOf(JedisDataException.class).hasMessageStartingWith("WRONGTYPE");
    // SET overwrites whatever the key held, as in Redis
    jedis.set(l, "now a string");
    assertThat(jedis.type(l)).isEqualTo("string");
  }

  @Test
  void setCommands() {
    try (final Jedis jedis = connect()) {
      assertSetCommands(jedis, "");
    }
  }

  @Test
  void setCommandsOnADatabaseKey() {
    try (final Jedis jedis = connect()) {
      assertSetCommands(jedis, getDatabaseName() + ".");
    }
  }

  private void assertSetCommands(final Jedis jedis, final String prefix) {
    final String a = prefix + "set:a";
    final String b = prefix + "set:b";
    assertThat(jedis.sadd(a, "1", "2", "3")).isEqualTo(3L);
    assertThat(jedis.sadd(a, "2")).isEqualTo(0L);
    assertThat(jedis.type(a)).isEqualTo("set");
    assertThat(jedis.scard(a)).isEqualTo(3L);
    assertThat(jedis.sismember(a, "2")).isTrue();
    assertThat(jedis.sismember(a, "9")).isFalse();
    assertThat(jedis.smembers(a)).containsExactlyInAnyOrder("1", "2", "3");
    assertThat(jedis.srandmember(a)).isIn("1", "2", "3");

    assertThat(jedis.srem(a, "2", "9")).isEqualTo(1L);
    assertThat(jedis.smembers(a)).containsExactlyInAnyOrder("1", "3");

    jedis.sadd(b, "2", "3", "4");
    assertThat(jedis.sdiff(a, b)).containsExactlyInAnyOrder("1");
    assertThat(jedis.sinter(a, b)).containsExactlyInAnyOrder("3");
    assertThat(jedis.sunion(a, b)).containsExactlyInAnyOrder("1", "2", "3", "4");
    assertThat(jedis.sinter(a, prefix + "set:missing")).isEmpty();

    assertThat(jedis.sdiffstore(prefix + "set:diffStore", a, b)).isEqualTo(1L);
    assertThat(jedis.smembers(prefix + "set:diffStore")).containsExactlyInAnyOrder("1");
    assertThat(jedis.sinterstore(prefix + "set:interStore", a, b)).isEqualTo(1L);
    assertThat(jedis.smembers(prefix + "set:interStore")).containsExactlyInAnyOrder("3");
    assertThat(jedis.sunionstore(prefix + "set:unionStore", a, b)).isEqualTo(4L);
    assertThat(jedis.smembers(prefix + "set:unionStore")).containsExactlyInAnyOrder("1", "2", "3", "4");
    // An empty result removes the destination
    assertThat(jedis.sinterstore(prefix + "set:unionStore", a, prefix + "set:missing")).isZero();
    assertThat(jedis.exists(prefix + "set:unionStore")).isFalse();

    jedis.sadd(prefix + "set:solo", "only");
    assertThat(jedis.spop(prefix + "set:solo")).isEqualTo("only");
    assertThat(jedis.exists(prefix + "set:solo")).isFalse();
    assertThat(jedis.spop(prefix + "set:solo")).isNull();
    assertThat(jedis.srandmember(prefix + "set:solo")).isNull();

    jedis.set(prefix + "set:string", "plain");
    assertThatThrownBy(() -> jedis.sadd(prefix + "set:string", "x")).isInstanceOf(JedisDataException.class)
        .hasMessageStartingWith("WRONGTYPE");
    assertThatThrownBy(() -> jedis.sunion(a, prefix + "set:string")).isInstanceOf(JedisDataException.class)
        .hasMessageStartingWith("WRONGTYPE");
  }

  @Test
  void connectionCommands() {
    try (final Jedis jedis = connect()) {
      assertThat(jedis.clientGetname()).isNull();
      assertThat(jedis.clientSetname("test-client")).isEqualTo("OK");
      assertThat(jedis.clientId()).isPositive();
      assertThat(jedis.info()).contains("redis_version");
    }
  }

  @Test
  void quitRepliesThenCloses() throws IOException {
    try (final Socket socket = new Socket("localhost", getServerRedisPort())) {
      socket.setSoTimeout(5_000);
      final OutputStream out = socket.getOutputStream();
      out.write(("*3\r\n$4\r\nAUTH\r\n$4\r\nroot\r\n$" + DEFAULT_PASSWORD_FOR_TESTS.length() + "\r\n" + DEFAULT_PASSWORD_FOR_TESTS
          + "\r\n*1\r\n$4\r\nQUIT\r\n").getBytes(StandardCharsets.US_ASCII));
      out.flush();

      final InputStream in = socket.getInputStream();
      final StringBuilder reply = new StringBuilder();
      int b;
      while ((b = in.read()) != -1)
        reply.append((char) b);
      // Both replies, then EOF: the server closed its end after QUIT's reply
      assertThat(reply.toString()).isEqualTo("+OK\r\n+OK\r\n");
    }
  }

  @Test
  void wrongArityIsRefused() {
    try (final Jedis jedis = connect()) {
      assertThatThrownBy(() -> jedis.sendCommand(Protocol.Command.LPUSH, "only-key")).isInstanceOf(JedisDataException.class)
          .hasMessageContaining("wrong number of arguments");
      assertThatThrownBy(() -> jedis.sendCommand(Protocol.Command.TTL)).isInstanceOf(JedisDataException.class)
          .hasMessageContaining("wrong number of arguments");
    }
  }

  private Jedis connect() {
    final Jedis jedis = new Jedis("localhost", getServerRedisPort());
    jedis.auth("root", DEFAULT_PASSWORD_FOR_TESTS);
    return jedis;
  }

  @Override
  protected void populateDatabase() {
  }

  @Override
  public void setTestConfiguration() {
    super.setTestConfiguration();
    GlobalConfiguration.SERVER_PLUGINS.setValue("Redis Protocol:com.arcadedb.redis.RedisProtocolPlugin");
  }

  @AfterEach
  @Override
  public void endTest() {
    GlobalConfiguration.SERVER_PLUGINS.setValue("");
    super.endTest();
  }
}
