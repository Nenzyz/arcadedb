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

/**
 * The glob of the KEYS command, with Redis' syntax: {@code *}, {@code ?}, {@code [abc]}, {@code [^abc]}, {@code [a-z]} and
 * {@code \} to escape. Matched directly, not compiled to a regular expression: the pattern comes from the client, and a regex
 * built from it ({@code a*a*a*...b}) can backtrack exponentially. Every token but {@code *} consumes exactly one character, so
 * remembering only the last {@code *} is enough, and the match is O(pattern x text) at worst.
 */
final class RedisGlob {
  private RedisGlob() {
  }

  static boolean matches(final String pattern, final String text) {
    final int patternLength = pattern.length();
    final int textLength = text.length();
    int p = 0;
    int t = 0;
    int starP = -1;
    int starT = 0;

    while (t < textLength) {
      if (p < patternLength && pattern.charAt(p) == '*') {
        // collapse a run of stars, then try to match the rest with the star matching nothing first
        while (p < patternLength && pattern.charAt(p) == '*')
          p++;
        if (p == patternLength)
          return true;
        starP = p;
        starT = t;
        continue;
      }

      final int next = p < patternLength ? matchOne(pattern, p, text.charAt(t)) : -1;
      if (next >= 0) {
        p = next;
        t++;
      } else if (starP >= 0) {
        // let the last star swallow one more character
        p = starP;
        t = ++starT;
      } else
        return false;
    }

    while (p < patternLength && pattern.charAt(p) == '*')
      p++;
    return p == patternLength;
  }

  /**
   * Matches the single-character token at {@code p} against {@code c}.
   *
   * @return the position after the token when it matches, -1 otherwise
   */
  private static int matchOne(final String pattern, int p, final char c) {
    final int length = pattern.length();
    final char token = pattern.charAt(p);
    switch (token) {
    case '?':
      return p + 1;
    case '\\':
      if (p + 1 < length)
        return pattern.charAt(p + 1) == c ? p + 2 : -1;
      return token == c ? p + 1 : -1;
    case '[': {
      p++;
      final boolean negated = p < length && pattern.charAt(p) == '^';
      if (negated)
        p++;
      boolean matched = false;
      while (p < length && pattern.charAt(p) != ']') {
        char from = pattern.charAt(p);
        if (from == '\\' && p + 1 < length)
          from = pattern.charAt(++p);
        if (p + 2 < length && pattern.charAt(p + 1) == '-' && pattern.charAt(p + 2) != ']') {
          char to = pattern.charAt(p + 2);
          p += 2;
          if (to == '\\' && p + 1 < length)
            to = pattern.charAt(++p);
          if (from > to) {
            final char swap = from;
            from = to;
            to = swap;
          }
          if (c >= from && c <= to)
            matched = true;
        } else if (from == c)
          matched = true;
        p++;
      }
      // an unterminated class runs to the end of the pattern, as in Redis
      if (p < length)
        p++;
      return matched != negated ? p : -1;
    }
    default:
      return token == c ? p + 1 : -1;
    }
  }
}
