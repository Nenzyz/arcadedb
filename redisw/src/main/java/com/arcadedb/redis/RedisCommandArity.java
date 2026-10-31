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

import java.util.Locale;
import java.util.Map;

/**
 * Argument counts (command name included) real Redis enforces before it runs a command: {min, max}, max -1 = no upper
 * bound. One table for both surfaces, the RESP wire path ({@code RedisNetworkExecutor}, #9059) and the {@code redis} query
 * language ({@code RedisQueryEngine}, #9161), so they cannot disagree about what a command accepts: without it a surplus
 * argument was read as the INCR/DECR amount, a missing one defaulted to 1, and a missing argument surfaced as
 * {@code Index 1 out of bounds for length 1}.
 * <p>
 * Intentionally partial: only the commands these surfaces dispatch with a fixed shape are listed. The hash commands are
 * listed apart because their shape is the wire one ({@code HGET <bucket> <key>}); the query language names an index or a
 * RID instead and checks its own forms.
 *
 * @author Luca Garulli (l.garulli@arcadedata.com)
 */
public final class RedisCommandArity {
  /** The commands that work on the shared RAM map: same shape on the wire and in the query language. */
  private static final Map<String, int[]> RAM = Map.ofEntries(//
      Map.entry("DECR", new int[] { 2, 2 }), Map.entry("DECRBY", new int[] { 3, 3 }),//
      Map.entry("INCR", new int[] { 2, 2 }), Map.entry("INCRBY", new int[] { 3, 3 }),//
      Map.entry("INCRBYFLOAT", new int[] { 3, 3 }),//
      Map.entry("GET", new int[] { 2, 2 }), Map.entry("GETDEL", new int[] { 2, 2 }),//
      Map.entry("EXISTS", new int[] { 2, -1 }), Map.entry("SET", new int[] { 3, -1 }));

  /**
   * The key space commands only the wire path dispatches: keys, strings, lists, sets, expiry and connection. EXPIRE's NX/XX/GT/LT
   * options and the COUNT of LPOP/RPOP/SPOP/SRANDMEMBER are not supported, so their shapes are the ones without them.
   */
  private static final Map<String, int[]> WIRE_KEYSPACE = Map.ofEntries(//
      Map.entry("ECHO", new int[] { 2, 2 }), Map.entry("DEL", new int[] { 2, -1 }), Map.entry("UNLINK", new int[] { 2, -1 }),//
      Map.entry("TYPE", new int[] { 2, 2 }), Map.entry("EXPIRE", new int[] { 3, 3 }), Map.entry("PEXPIRE", new int[] { 3, 3 }),//
      Map.entry("EXPIREAT", new int[] { 3, 3 }), Map.entry("PEXPIREAT", new int[] { 3, 3 }), Map.entry("TTL", new int[] { 2, 2 }),//
      Map.entry("PTTL", new int[] { 2, 2 }), Map.entry("PERSIST", new int[] { 2, 2 }), Map.entry("KEYS", new int[] { 2, 2 }),//
      Map.entry("RANDOMKEY", new int[] { 1, 1 }), Map.entry("RENAME", new int[] { 3, 3 }), Map.entry("RENAMENX", new int[] { 3, 3 }),//
      Map.entry("DBSIZE", new int[] { 1, 1 }), Map.entry("FLUSHDB", new int[] { 1, 2 }), Map.entry("FLUSHALL", new int[] { 1, 2 }),//
      Map.entry("APPEND", new int[] { 3, 3 }), Map.entry("STRLEN", new int[] { 2, 2 }), Map.entry("GETRANGE", new int[] { 4, 4 }),//
      Map.entry("SETRANGE", new int[] { 4, 4 }), Map.entry("MGET", new int[] { 2, -1 }), Map.entry("MSET", new int[] { 3, -1 }),//
      Map.entry("MSETNX", new int[] { 3, -1 }), Map.entry("GETSET", new int[] { 3, 3 }), Map.entry("SETEX", new int[] { 4, 4 }),//
      Map.entry("PSETEX", new int[] { 4, 4 }), Map.entry("SETNX", new int[] { 3, 3 }),//
      Map.entry("LPUSH", new int[] { 3, -1 }), Map.entry("RPUSH", new int[] { 3, -1 }), Map.entry("LPOP", new int[] { 2, 2 }),//
      Map.entry("RPOP", new int[] { 2, 2 }), Map.entry("LLEN", new int[] { 2, 2 }), Map.entry("LRANGE", new int[] { 4, 4 }),//
      Map.entry("LINDEX", new int[] { 3, 3 }), Map.entry("LSET", new int[] { 4, 4 }), Map.entry("LINSERT", new int[] { 5, 5 }),//
      Map.entry("LREM", new int[] { 4, 4 }), Map.entry("LTRIM", new int[] { 4, 4 }), Map.entry("RPOPLPUSH", new int[] { 3, 3 }),//
      Map.entry("SADD", new int[] { 3, -1 }), Map.entry("SREM", new int[] { 3, -1 }), Map.entry("SISMEMBER", new int[] { 3, 3 }),//
      Map.entry("SMEMBERS", new int[] { 2, 2 }), Map.entry("SCARD", new int[] { 2, 2 }), Map.entry("SRANDMEMBER", new int[] { 2, 2 }),//
      Map.entry("SPOP", new int[] { 2, 2 }), Map.entry("SDIFF", new int[] { 2, -1 }), Map.entry("SINTER", new int[] { 2, -1 }),//
      Map.entry("SUNION", new int[] { 2, -1 }), Map.entry("SDIFFSTORE", new int[] { 3, -1 }),//
      Map.entry("SINTERSTORE", new int[] { 3, -1 }), Map.entry("SUNIONSTORE", new int[] { 3, -1 }),//
      Map.entry("CLIENT", new int[] { 2, -1 }), Map.entry("COMMAND", new int[] { 1, -1 }), Map.entry("INFO", new int[] { 1, -1 }));

  /** The hash commands in their wire shape. */
  private static final Map<String, int[]> WIRE_HASH = Map.ofEntries(//
      Map.entry("HDEL", new int[] { 2, -1 }), Map.entry("HEXISTS", new int[] { 3, 3 }),//
      Map.entry("HGET", new int[] { 3, 3 }), Map.entry("HMGET", new int[] { 3, -1 }),//
      Map.entry("HSET", new int[] { 3, -1 }), Map.entry("HMSET", new int[] { 3, -1 }));

  private RedisCommandArity() {
  }

  /**
   * Refuses a RAM command (the ones the wire path and the query language share) called with a wrong number of arguments.
   *
   * @param command       the upper-case command name
   * @param argumentCount the arguments including the command name
   *
   * @throws RedisException when the count is outside the command's bounds; a caller that is not the wire path translates it to its own
   *                        kind of client error (the query language answers a {@code CommandParsingException}, an HTTP 4xx)
   */
  public static void checkRam(final String command, final int argumentCount) {
    check(RAM.get(command), command, argumentCount);
  }

  /** Same as {@link #checkRam} for every command of the wire protocol, the key space and hash commands included. */
  public static void checkWire(final String command, final int argumentCount) {
    int[] arity = RAM.get(command);
    if (arity == null)
      arity = WIRE_KEYSPACE.get(command);
    check(arity != null ? arity : WIRE_HASH.get(command), command, argumentCount);
  }

  private static void check(final int[] arity, final String command, final int argumentCount) {
    if (arity != null && (argumentCount < arity[0] || (arity[1] >= 0 && argumentCount > arity[1])))
      throw wrongArity(command);
  }

  public static RedisException wrongArity(final String command) {
    return new RedisException("wrong number of arguments for '" + command.toLowerCase(Locale.ROOT) + "' command");
  }
}
