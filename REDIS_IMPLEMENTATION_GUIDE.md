# Redis Commands Implementation Guide for ArcadeDB

This document provides detailed implementation specifications for each missing Redis command, with code examples and Redis source references.

---

## Table of Contents
- [Phase 1: Core Key-Value Commands](#phase-1-core-key-value-commands)
- [Phase 2: Enhanced String Commands](#phase-2-enhanced-string-commands)
- [Phase 3: List Commands](#phase-3-list-commands)
- [Phase 4: Set Commands](#phase-4-set-commands)
- [Phase 5: Sorted Set Commands](#phase-5-sorted-set-commands)
- [Phase 6: Hash Commands](#phase-6-hash-commands)

---

# Phase 1: Core Key-Value Commands

## 1. DEL - Delete Keys

**Redis Source**: `redis/src/db.c:delGenericCommand()`

### Specification
```
DEL key [key ...]
```
- Removes specified keys
- Returns: Number of keys deleted
- Non-existing keys are ignored

### Implementation

```java
private void del(final List<Object> list) {
    int deleted = 0;

    for (int i = 1; i < list.size(); i++) {
        final String key = (String) list.get(i);

        // Try to delete from all storage types
        if (defaultBucket.remove(key) != null) {
            deleted++;
            expirationMap.remove(key); // Clean up expiration
        } else if (listBucket.remove(key) != null) {
            deleted++;
            expirationMap.remove(key);
        } else if (setBucket.remove(key) != null) {
            deleted++;
            expirationMap.remove(key);
        } else if (zsetBucket.remove(key) != null) {
            deleted++;
            expirationMap.remove(key);
        }
        // Note: Hash keys are in persistent storage, handled separately
    }

    respondValue(deleted, false);
}
```

### Redis Pattern
```c
// From redis/src/db.c
void delGenericCommand(client *c, int flags) {
    int deleted = 0, j;
    for (j = 1; j < c->argc; j++) {
        int ok = dbSyncDelete(c->db, c->argv[j]);
        if (ok) deleted++;
    }
    addReplyLongLong(c,deleted);
}
```

### Test Cases
```java
@Test
public void testDel() {
    jedis.set("key1", "value1");
    jedis.set("key2", "value2");
    jedis.set("key3", "value3");

    assertEquals(2L, jedis.del("key1", "key2"));
    assertNull(jedis.get("key1"));
    assertNull(jedis.get("key2"));
    assertEquals("value3", jedis.get("key3"));

    // Delete non-existing key
    assertEquals(0L, jedis.del("nonexistent"));
}
```

---

## 2. TYPE - Get Key Type

**Redis Source**: `redis/src/db.c:typeCommand()`

### Specification
```
TYPE key
```
- Returns: string, list, set, zset, hash, or none

### Implementation

```java
private enum RedisType {
    STRING("string"),
    LIST("list"),
    SET("set"),
    ZSET("zset"),
    HASH("hash"),
    NONE("none");

    private final String typeName;
    RedisType(String typeName) { this.typeName = typeName; }
    public String getTypeName() { return typeName; }
}

private RedisType getKeyType(final String key) {
    // Check expiration first
    if (isExpired(key)) {
        deleteExpiredKey(key);
        return RedisType.NONE;
    }

    if (defaultBucket.containsKey(key)) return RedisType.STRING;
    if (listBucket.containsKey(key)) return RedisType.LIST;
    if (setBucket.containsKey(key)) return RedisType.SET;
    if (zsetBucket.containsKey(key)) return RedisType.ZSET;

    // Check persistent storage for hash
    try {
        if (getRecord(databaseName, key) != null) {
            return RedisType.HASH;
        }
    } catch (Exception e) {
        // Key doesn't exist in persistent storage
    }

    return RedisType.NONE;
}

private void type(final List<Object> list) {
    final String key = (String) list.get(1);
    final RedisType type = getKeyType(key);

    value.append("+");
    value.append(type.getTypeName());
}
```

### Redis Pattern
```c
// From redis/src/db.c
void typeCommand(client *c) {
    robj *o = lookupKeyReadWithFlags(c->db,c->argv[1],LOOKUP_NOTOUCH);
    char *type = "none";
    if (o) {
        switch(o->type) {
            case OBJ_STRING: type = "string"; break;
            case OBJ_LIST: type = "list"; break;
            case OBJ_SET: type = "set"; break;
            case OBJ_ZSET: type = "zset"; break;
            case OBJ_HASH: type = "hash"; break;
        }
    }
    addReplyStatus(c,type);
}
```

### Test Cases
```java
@Test
public void testType() {
    jedis.set("mykey", "value");
    assertEquals("string", jedis.type("mykey"));

    jedis.lpush("mylist", "value");
    assertEquals("list", jedis.type("mylist"));

    jedis.sadd("myset", "value");
    assertEquals("set", jedis.type("myset"));

    assertEquals("none", jedis.type("nonexistent"));
}
```

---

## 3. EXPIRE - Set Key Expiration

**Redis Source**: `redis/src/expire.c:expireGenericCommand()`

### Specification
```
EXPIRE key seconds
```
- Sets timeout on key
- Returns: 1 if timeout was set, 0 if key doesn't exist

### Implementation

```java
// Add these fields to RedisNetworkExecutor
private final Map<String, Long> expirationMap = new ConcurrentHashMap<>();
private final ScheduledExecutorService expirationService;

public RedisNetworkExecutor(final ArcadeDBServer server, final Socket socket) throws IOException {
    // ... existing code ...

    // Start expiration cleanup task
    expirationService = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Redis-Expiration-Cleaner");
        t.setDaemon(true);
        return t;
    });

    expirationService.scheduleAtFixedRate(this::cleanupExpiredKeys, 1, 1, TimeUnit.SECONDS);
}

private void expire(final List<Object> list) {
    final String key = (String) list.get(1);
    final int seconds = Integer.parseInt((String) list.get(2));

    // Check if key exists
    if (!keyExists(key)) {
        respondValue(0, false);
        return;
    }

    // Set expiration time
    final long expirationTime = System.currentTimeMillis() + (seconds * 1000L);
    expirationMap.put(key, expirationTime);

    respondValue(1, false);
}

private boolean keyExists(final String key) {
    if (isExpired(key)) {
        deleteExpiredKey(key);
        return false;
    }
    return defaultBucket.containsKey(key)
        || listBucket.containsKey(key)
        || setBucket.containsKey(key)
        || zsetBucket.containsKey(key);
}

private boolean isExpired(final String key) {
    Long expirationTime = expirationMap.get(key);
    return expirationTime != null && expirationTime < System.currentTimeMillis();
}

private void deleteExpiredKey(final String key) {
    expirationMap.remove(key);
    defaultBucket.remove(key);
    listBucket.remove(key);
    setBucket.remove(key);
    zsetBucket.remove(key);
}

private void cleanupExpiredKeys() {
    final long now = System.currentTimeMillis();
    final Iterator<Map.Entry<String, Long>> iterator = expirationMap.entrySet().iterator();

    while (iterator.hasNext()) {
        Map.Entry<String, Long> entry = iterator.next();
        if (entry.getValue() < now) {
            deleteExpiredKey(entry.getKey());
            iterator.remove();
        }
    }
}

@Override
public void close() {
    shutdown = true;
    if (expirationService != null) {
        expirationService.shutdown();
    }
    if (channel != null) {
        channel.close();
    }
}
```

### Redis Pattern
```c
// From redis/src/expire.c
void expireGenericCommand(client *c, long long basetime, int unit) {
    robj *key = c->argv[1];
    long long when;

    if (getLongLongFromObjectOrReply(c, c->argv[2], &when, NULL) != C_OK)
        return;

    int already_expired = 0;
    if (unit == UNIT_SECONDS) when *= 1000;
    when += basetime;

    if (when <= commandTimeSnapshot() && !server.loading) {
        already_expired = 1;
    }

    if (lookupKeyWrite(c->db,key) == NULL) {
        addReply(c,shared.czero);
        return;
    }

    if (already_expired) {
        /* Delete the key */
        server.dirty++;
        notifyKeyspaceEvent(NOTIFY_GENERIC,"del",key,c->db->id);
        int deleted = dbSyncDelete(c->db,key);
        addReply(c, deleted ? shared.cone : shared.czero);
    } else {
        setExpire(c,c->db,key,when);
        addReply(c,shared.cone);
    }
}
```

### Test Cases
```java
@Test
public void testExpire() throws InterruptedException {
    jedis.set("mykey", "value");

    // Set 2 second expiration
    assertEquals(1L, jedis.expire("mykey", 2));
    assertTrue(jedis.exists("mykey"));

    // Wait for expiration
    Thread.sleep(2500);
    assertFalse(jedis.exists("mykey"));

    // Expire non-existing key
    assertEquals(0L, jedis.expire("nonexistent", 10));
}
```

---

## 4. TTL - Get Time To Live

**Redis Source**: `redis/src/expire.c:ttlGenericCommand()`

### Specification
```
TTL key
```
- Returns: TTL in seconds, -2 if key doesn't exist, -1 if no expiration

### Implementation

```java
private void ttl(final List<Object> list) {
    final String key = (String) list.get(1);

    // Check if key exists
    if (!keyExists(key)) {
        respondValue(-2, false);
        return;
    }

    // Check if key has expiration
    Long expirationTime = expirationMap.get(key);
    if (expirationTime == null) {
        respondValue(-1, false);
        return;
    }

    // Calculate remaining TTL
    long remainingMs = expirationTime - System.currentTimeMillis();
    if (remainingMs < 0) {
        // Key expired but not yet cleaned up
        deleteExpiredKey(key);
        respondValue(-2, false);
    } else {
        long remainingSeconds = remainingMs / 1000;
        respondValue(remainingSeconds, false);
    }
}
```

### Redis Pattern
```c
// From redis/src/expire.c
void ttlGenericCommand(client *c, int output_ms, int output_abs) {
    long long expire, ttl = -1;

    if (lookupKeyReadWithFlags(c->db,c->argv[1],LOOKUP_NOTOUCH) == NULL) {
        addReplyLongLong(c,-2);
        return;
    }

    expire = getExpire(c->db,c->argv[1]);
    if (expire != -1) {
        ttl = expire - commandTimeSnapshot();
        if (ttl < 0) ttl = 0;
    }

    if (ttl == -1) {
        addReplyLongLong(c,-1);
    } else {
        addReplyLongLong(c, output_ms ? ttl : ((ttl+500)/1000));
    }
}
```

### Test Cases
```java
@Test
public void testTTL() {
    jedis.set("mykey", "value");

    // No expiration
    assertEquals(-1L, jedis.ttl("mykey"));

    // Set 10 second expiration
    jedis.expire("mykey", 10);
    long ttl = jedis.ttl("mykey");
    assertTrue(ttl >= 9 && ttl <= 10);

    // Non-existing key
    assertEquals(-2L, jedis.ttl("nonexistent"));
}
```

---

## 5. PERSIST - Remove Expiration

**Redis Source**: `redis/src/expire.c:persistCommand()`

### Specification
```
PERSIST key
```
- Removes expiration from key
- Returns: 1 if expiration removed, 0 if key doesn't exist or has no expiration

### Implementation

```java
private void persist(final List<Object> list) {
    final String key = (String) list.get(1);

    // Check if key exists and has expiration
    if (!keyExists(key)) {
        respondValue(0, false);
        return;
    }

    // Remove expiration
    if (expirationMap.remove(key) != null) {
        respondValue(1, false);
    } else {
        respondValue(0, false);
    }
}
```

### Redis Pattern
```c
// From redis/src/expire.c
void persistCommand(client *c) {
    if (lookupKeyWrite(c->db,c->argv[1]) == NULL) {
        addReply(c,shared.czero);
    } else {
        if (removeExpire(c->db,c->argv[1])) {
            signalModifiedKey(c,c->db,c->argv[1]);
            notifyKeyspaceEvent(NOTIFY_GENERIC,"persist",c->argv[1],c->db->id);
            addReply(c,shared.cone);
            server.dirty++;
        } else {
            addReply(c,shared.czero);
        }
    }
}
```

### Test Cases
```java
@Test
public void testPersist() {
    jedis.set("mykey", "value");
    jedis.expire("mykey", 10);

    // Key has expiration
    assertTrue(jedis.ttl("mykey") > 0);

    // Remove expiration
    assertEquals(1L, jedis.persist("mykey"));
    assertEquals(-1L, jedis.ttl("mykey"));

    // Key with no expiration
    assertEquals(0L, jedis.persist("mykey"));

    // Non-existing key
    assertEquals(0L, jedis.persist("nonexistent"));
}
```

---

## 6. KEYS - Find Keys Matching Pattern

**Redis Source**: `redis/src/db.c:keysCommand()`

### Specification
```
KEYS pattern
```
- Returns: Array of keys matching pattern
- Patterns: `*` = any, `?` = single char, `[abc]` = one of
- Warning: O(N) operation

### Implementation

```java
private void keys(final List<Object> list) {
    final String pattern = (String) list.get(1);
    final List<String> matchingKeys = new ArrayList<>();

    // Compile pattern to regex
    final String regex = globToRegex(pattern);
    final Pattern compiledPattern = Pattern.compile(regex);

    // Check all key types
    addMatchingKeys(defaultBucket.keySet(), compiledPattern, matchingKeys);
    addMatchingKeys(listBucket.keySet(), compiledPattern, matchingKeys);
    addMatchingKeys(setBucket.keySet(), compiledPattern, matchingKeys);
    addMatchingKeys(zsetBucket.keySet(), compiledPattern, matchingKeys);

    // Return array of keys
    value.append("*");
    value.append(matchingKeys.size());

    for (String key : matchingKeys) {
        appendCrLf();
        value.append("$");
        value.append(key.length());
        appendCrLf();
        value.append(key);
    }
}

private void addMatchingKeys(Set<String> keys, Pattern pattern, List<String> result) {
    for (String key : keys) {
        if (!isExpired(key) && pattern.matcher(key).matches()) {
            result.add(key);
        }
    }
}

private String globToRegex(String glob) {
    StringBuilder regex = new StringBuilder("^");
    for (int i = 0; i < glob.length(); i++) {
        char c = glob.charAt(i);
        switch (c) {
            case '*':
                regex.append(".*");
                break;
            case '?':
                regex.append(".");
                break;
            case '[':
                int j = i;
                if (j < glob.length() && glob.charAt(j) == '!') j++;
                if (j < glob.length() && glob.charAt(j) == ']') j++;
                while (j < glob.length() && glob.charAt(j) != ']') j++;
                if (j >= glob.length()) {
                    regex.append("\\[");
                } else {
                    String charClass = glob.substring(i, j + 1);
                    charClass = charClass.replace("!", "^");
                    regex.append(charClass);
                    i = j;
                }
                break;
            case '\\':
                if (i < glob.length() - 1) {
                    regex.append(Pattern.quote(String.valueOf(glob.charAt(++i))));
                }
                break;
            default:
                regex.append(Pattern.quote(String.valueOf(c)));
        }
    }
    regex.append("$");
    return regex.toString();
}
```

### Redis Pattern
```c
// From redis/src/db.c
void keysCommand(client *c) {
    dictIterator *di;
    dictEntry *de;
    sds pattern = c->argv[1]->ptr;
    int plen = sdslen(pattern);
    int allkeys = (pattern[0] == '*' && plen == 1);
    unsigned long numkeys = 0;
    void *replylen = addReplyDeferredLen(c);

    di = dictGetSafeIterator(c->db->dict);
    while((de = dictNext(di)) != NULL) {
        sds key = dictGetKey(de);
        if (allkeys || stringmatchlen(pattern,plen,key,sdslen(key),0)) {
            if (expireIfNeeded(c->db,keyobj,0) == 0) {
                addReplyBulkCBuffer(c,key,sdslen(key));
                numkeys++;
            }
        }
    }
    dictReleaseIterator(di);
    setDeferredArrayLen(c,replylen,numkeys);
}
```

### Test Cases
```java
@Test
public void testKeys() {
    jedis.set("key1", "value");
    jedis.set("key2", "value");
    jedis.set("another", "value");
    jedis.set("key10", "value");

    // Get all keys
    Set<String> keys = jedis.keys("*");
    assertTrue(keys.contains("key1"));
    assertTrue(keys.contains("key2"));
    assertTrue(keys.contains("another"));

    // Pattern matching
    keys = jedis.keys("key*");
    assertEquals(3, keys.size());
    assertTrue(keys.contains("key1"));
    assertFalse(keys.contains("another"));

    // Single character wildcard
    keys = jedis.keys("key?");
    assertEquals(2, keys.size());

    // Character class
    keys = jedis.keys("key[12]");
    assertEquals(2, keys.size());
}
```

---

## 7. RANDOMKEY - Return Random Key

**Redis Source**: `redis/src/db.c:randomkeyCommand()`

### Specification
```
RANDOMKEY
```
- Returns: Random key name, or nil if database is empty

### Implementation

```java
private void randomKey(final List<Object> list) {
    // Collect all keys
    final List<String> allKeys = new ArrayList<>();
    allKeys.addAll(defaultBucket.keySet());
    allKeys.addAll(listBucket.keySet());
    allKeys.addAll(setBucket.keySet());
    allKeys.addAll(zsetBucket.keySet());

    // Remove expired keys
    allKeys.removeIf(this::isExpired);

    if (allKeys.isEmpty()) {
        value.append("$-1");
    } else {
        // Select random key
        final String randomKey = allKeys.get(ThreadLocalRandom.current().nextInt(allKeys.size()));
        respondValue(randomKey, true);
    }
}
```

### Redis Pattern
```c
// From redis/src/db.c
void randomkeyCommand(client *c) {
    robj *key;
    if ((key = dbRandomKey(c->db)) == NULL) {
        addReplyNull(c);
        return;
    }
    addReplyBulk(c,key);
    decrRefCount(key);
}
```

### Test Cases
```java
@Test
public void testRandomKey() {
    // Empty database
    assertNull(jedis.randomKey());

    // Add keys
    Set<String> keys = new HashSet<>();
    keys.add("key1");
    keys.add("key2");
    keys.add("key3");

    for (String key : keys) {
        jedis.set(key, "value");
    }

    // Get random key - should be one of our keys
    String randomKey = jedis.randomKey();
    assertTrue(keys.contains(randomKey));

    // Test randomness (statistical test)
    Map<String, Integer> counts = new HashMap<>();
    for (int i = 0; i < 1000; i++) {
        String key = jedis.randomKey();
        counts.put(key, counts.getOrDefault(key, 0) + 1);
    }

    // Each key should be selected roughly 333 times (allow variance)
    for (int count : counts.values()) {
        assertTrue(count > 200 && count < 467); // ~33% ± 10%
    }
}
```

---

## 8. RENAME - Rename Key

**Redis Source**: `redis/src/db.c:renameGenericCommand()`

### Specification
```
RENAME key newkey
```
- Renames key to newkey
- Overwrites newkey if it exists
- Error if key doesn't exist

### Implementation

```java
private void rename(final List<Object> list) {
    final String oldKey = (String) list.get(1);
    final String newKey = (String) list.get(2);

    // Check if source key exists
    if (!keyExists(oldKey)) {
        throw new RedisException("ERR no such key");
    }

    // Get the value and type
    final RedisType type = getKeyType(oldKey);

    // Delete the new key if it exists
    if (keyExists(newKey)) {
        deleteKey(newKey);
    }

    // Move the value
    switch (type) {
        case STRING:
            defaultBucket.put(newKey, defaultBucket.remove(oldKey));
            break;
        case LIST:
            listBucket.put(newKey, listBucket.remove(oldKey));
            break;
        case SET:
            setBucket.put(newKey, setBucket.remove(oldKey));
            break;
        case ZSET:
            zsetBucket.put(newKey, zsetBucket.remove(oldKey));
            break;
        default:
            throw new RedisException("ERR unsupported type");
    }

    // Move expiration if it exists
    Long expiration = expirationMap.remove(oldKey);
    if (expiration != null) {
        expirationMap.put(newKey, expiration);
    }

    value.append("+OK");
}

private void deleteKey(final String key) {
    defaultBucket.remove(key);
    listBucket.remove(key);
    setBucket.remove(key);
    zsetBucket.remove(key);
    expirationMap.remove(key);
}
```

### Redis Pattern
```c
// From redis/src/db.c
void renameGenericCommand(client *c, int nx) {
    robj *o;

    /* Source key must exist */
    if ((o = lookupKeyWriteOrReply(c,c->argv[1],shared.nokeyerr)) == NULL)
        return;

    incrRefCount(o);

    /* If source and dest are the same, just return */
    if (sdscmp(c->argv[1]->ptr,c->argv[2]->ptr) == 0) {
        decrRefCount(o);
        addReply(c,nx ? shared.czero : shared.ok);
        return;
    }

    /* Check if target exists for RENAMENX */
    if (nx && lookupKeyWrite(c->db,c->argv[2]) != NULL) {
        decrRefCount(o);
        addReply(c,shared.czero);
        return;
    }

    dbDelete(c->db,c->argv[2]);
    dbAdd(c->db,c->argv[2],o);
    dbDelete(c->db,c->argv[1]);

    addReply(c,nx ? shared.cone : shared.ok);
}
```

### Test Cases
```java
@Test
public void testRename() {
    jedis.set("mykey", "value");

    // Rename key
    assertEquals("OK", jedis.rename("mykey", "newkey"));
    assertNull(jedis.get("mykey"));
    assertEquals("value", jedis.get("newkey"));

    // Rename to existing key (overwrites)
    jedis.set("another", "value2");
    jedis.rename("newkey", "another");
    assertEquals("value", jedis.get("another"));

    // Rename non-existing key
    try {
        jedis.rename("nonexistent", "newname");
        fail("Should throw exception");
    } catch (JedisDataException e) {
        assertTrue(e.getMessage().contains("no such key"));
    }
}
```

---

## 9. RENAMENX - Rename Key If New Key Doesn't Exist

**Redis Source**: `redis/src/db.c:renameGenericCommand()`

### Specification
```
RENAMENX key newkey
```
- Renames key only if newkey doesn't exist
- Returns: 1 if renamed, 0 if newkey exists

### Implementation

```java
private void renameNx(final List<Object> list) {
    final String oldKey = (String) list.get(1);
    final String newKey = (String) list.get(2);

    // Check if source key exists
    if (!keyExists(oldKey)) {
        throw new RedisException("ERR no such key");
    }

    // Check if destination key exists
    if (keyExists(newKey)) {
        respondValue(0, false);
        return;
    }

    // Use rename logic
    final RedisType type = getKeyType(oldKey);

    // Move the value
    switch (type) {
        case STRING:
            defaultBucket.put(newKey, defaultBucket.remove(oldKey));
            break;
        case LIST:
            listBucket.put(newKey, listBucket.remove(oldKey));
            break;
        case SET:
            setBucket.put(newKey, setBucket.remove(oldKey));
            break;
        case ZSET:
            zsetBucket.put(newKey, zsetBucket.remove(oldKey));
            break;
    }

    // Move expiration
    Long expiration = expirationMap.remove(oldKey);
    if (expiration != null) {
        expirationMap.put(newKey, expiration);
    }

    respondValue(1, false);
}
```

### Test Cases
```java
@Test
public void testRenameNx() {
    jedis.set("mykey", "value");

    // Rename to non-existing key
    assertEquals(1L, jedis.renamenx("mykey", "newkey"));
    assertEquals("value", jedis.get("newkey"));

    // Try to rename to existing key
    jedis.set("another", "value2");
    assertEquals(0L, jedis.renamenx("newkey", "another"));
    assertEquals("value", jedis.get("newkey"));
    assertEquals("value2", jedis.get("another"));
}
```

---

## 10. DBSIZE - Get Database Size

**Redis Source**: `redis/src/db.c:dbsizeCommand()`

### Specification
```
DBSIZE
```
- Returns: Number of keys in database

### Implementation

```java
private void dbSize(final List<Object> list) {
    // Clean up expired keys first
    cleanupExpiredKeys();

    int size = defaultBucket.size()
             + listBucket.size()
             + setBucket.size()
             + zsetBucket.size();

    respondValue(size, false);
}
```

### Redis Pattern
```c
// From redis/src/db.c
void dbsizeCommand(client *c) {
    addReplyLongLong(c,dictSize(c->db->dict));
}
```

### Test Cases
```java
@Test
public void testDbSize() {
    assertEquals(0L, jedis.dbSize());

    jedis.set("key1", "value1");
    jedis.set("key2", "value2");
    jedis.lpush("list1", "value");
    jedis.sadd("set1", "value");

    assertEquals(4L, jedis.dbSize());

    jedis.del("key1");
    assertEquals(3L, jedis.dbSize());
}
```

---

## 11. FLUSHDB - Delete All Keys

**Redis Source**: `redis/src/db.c:flushdbCommand()`

### Specification
```
FLUSHDB
```
- Deletes all keys from current database
- Returns: OK

### Implementation

```java
private void flushDb(final List<Object> list) {
    defaultBucket.clear();
    listBucket.clear();
    setBucket.clear();
    zsetBucket.clear();
    expirationMap.clear();

    value.append("+OK");
}
```

### Test Cases
```java
@Test
public void testFlushDb() {
    jedis.set("key1", "value1");
    jedis.set("key2", "value2");
    jedis.lpush("list1", "value");

    assertEquals("OK", jedis.flushDB());
    assertEquals(0L, jedis.dbSize());
    assertNull(jedis.get("key1"));
}
```

---

## 12. FLUSHALL - Delete All Keys From All Databases

**Redis Source**: `redis/src/db.c:flushallCommand()`

### Specification
```
FLUSHALL
```
- Deletes all keys from all databases
- In ArcadeDB implementation, same as FLUSHDB (single database)

### Implementation

```java
private void flushAll(final List<Object> list) {
    // Same as FLUSHDB for single database implementation
    flushDb(list);
}
```

---

# Phase 2: Enhanced String Commands

## 13. APPEND - Append to String

**Redis Source**: `redis/src/t_string.c:appendCommand()` (lines 705-741)

### Specification
```
APPEND key value
```
- Appends value to existing string
- If key doesn't exist, creates it
- Returns: Length of string after append

### Implementation

```java
private void append(final List<Object> list) {
    final String key = (String) list.get(1);
    final String appendValue = (String) list.get(2);

    // Check type
    if (keyExists(key)) {
        RedisType type = getKeyType(key);
        if (type != RedisType.STRING) {
            throw new RedisException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
    }

    // Get existing value or empty string
    String existing = (String) defaultBucket.getOrDefault(key, "");

    // Append
    String newValue = existing + appendValue;
    defaultBucket.put(key, newValue);

    // Return new length
    respondValue(newValue.length(), false);
}
```

### Redis Pattern
```c
// From redis/src/t_string.c
void appendCommand(client *c) {
    size_t totlen;
    robj *o, *append;

    o = lookupKeyWrite(c->db,c->argv[1]);
    if (o == NULL) {
        /* Create the key */
        c->argv[2] = tryObjectEncoding(c->argv[2]);
        dbAdd(c->db,c->argv[1],c->argv[2]);
        incrRefCount(c->argv[2]);
        totlen = stringObjectLen(c->argv[2]);
    } else {
        /* Key exists, check type */
        if (checkType(c,o,OBJ_STRING))
            return;

        append = c->argv[2];
        totlen = stringObjectLen(o)+sdslen(append->ptr);

        /* Append the value */
        o = dbUnshareStringValue(c->db,c->argv[1],o);
        o->ptr = sdscatlen(o->ptr,append->ptr,sdslen(append->ptr));
        totlen = sdslen(o->ptr);
    }

    addReplyLongLong(c,totlen);
}
```

### Test Cases
```java
@Test
public void testAppend() {
    // Append to non-existing key
    assertEquals(5L, jedis.append("mykey", "Hello"));
    assertEquals("Hello", jedis.get("mykey"));

    // Append to existing key
    assertEquals(11L, jedis.append("mykey", " World"));
    assertEquals("Hello World", jedis.get("mykey"));

    // Append to wrong type
    jedis.lpush("mylist", "value");
    try {
        jedis.append("mylist", "text");
        fail("Should throw exception");
    } catch (JedisDataException e) {
        assertTrue(e.getMessage().contains("WRONGTYPE"));
    }
}
```

---

## 14. STRLEN - Get String Length

**Redis Source**: `redis/src/t_string.c:strlenCommand()` (lines 743-748)

### Specification
```
STRLEN key
```
- Returns: Length of string, 0 if key doesn't exist

### Implementation

```java
private void strLen(final List<Object> list) {
    final String key = (String) list.get(1);

    if (!keyExists(key)) {
        respondValue(0, false);
        return;
    }

    // Check type
    RedisType type = getKeyType(key);
    if (type != RedisType.STRING) {
        throw new RedisException("WRONGTYPE Operation against a key holding the wrong kind of value");
    }

    String value = (String) defaultBucket.get(key);
    respondValue(value.length(), false);
}
```

### Redis Pattern
```c
// From redis/src/t_string.c
void strlenCommand(client *c) {
    robj *o;
    if ((o = lookupKeyReadOrReply(c,c->argv[1],shared.czero)) == NULL ||
        checkType(c,o,OBJ_STRING)) return;
    addReplyLongLong(c,stringObjectLen(o));
}
```

### Test Cases
```java
@Test
public void testStrLen() {
    jedis.set("mykey", "Hello World");
    assertEquals(11L, jedis.strlen("mykey"));

    // Non-existing key
    assertEquals(0L, jedis.strlen("nonexistent"));

    // Empty string
    jedis.set("empty", "");
    assertEquals(0L, jedis.strlen("empty"));
}
```

---

## 15. GETRANGE - Get Substring

**Redis Source**: `redis/src/t_string.c:getrangeCommand()` (lines 507-546)

### Specification
```
GETRANGE key start end
```
- Returns: Substring from start to end (inclusive)
- Negative indices count from end
- Out of range indices handled gracefully

### Implementation

```java
private void getRange(final List<Object> list) {
    final String key = (String) list.get(1);
    int start = Integer.parseInt((String) list.get(2));
    int end = Integer.parseInt((String) list.get(3));

    if (!keyExists(key)) {
        respondValue("", true);
        return;
    }

    // Check type
    RedisType type = getKeyType(key);
    if (type != RedisType.STRING) {
        throw new RedisException("WRONGTYPE Operation against a key holding the wrong kind of value");
    }

    String value = (String) defaultBucket.get(key);
    int length = value.length();

    // Handle negative indices
    if (start < 0) start = length + start;
    if (end < 0) end = length + end;

    // Clamp to valid range
    if (start < 0) start = 0;
    if (end >= length) end = length - 1;

    // Check if range is valid
    if (start > end || start >= length) {
        respondValue("", true);
    } else {
        String substring = value.substring(start, end + 1);
        respondValue(substring, true);
    }
}
```

### Redis Pattern
```c
// From redis/src/t_string.c
void getrangeCommand(client *c) {
    robj *o;
    long long start, end;
    char *str, llbuf[32];
    size_t strlen;

    if (getLongLongFromObjectOrReply(c,c->argv[2],&start,NULL) != C_OK)
        return;
    if (getLongLongFromObjectOrReply(c,c->argv[3],&end,NULL) != C_OK)
        return;
    if ((o = lookupKeyReadOrReply(c,c->argv[1],shared.emptybulk)) == NULL ||
        checkType(c,o,OBJ_STRING)) return;

    strlen = sdslen(str);

    /* Convert negative indexes */
    if (start < 0 && end < 0 && start > end) {
        addReply(c,shared.emptybulk);
        return;
    }
    if (start < 0) start = strlen+start;
    if (end < 0) end = strlen+end;
    if (start < 0) start = 0;
    if (end < 0) end = 0;
    if ((unsigned long long)end >= strlen) end = strlen-1;

    if (start > end || strlen == 0) {
        addReply(c,shared.emptybulk);
    } else {
        addReplyBulkCBuffer(c,(char*)str+start,end-start+1);
    }
}
```

### Test Cases
```java
@Test
public void testGetRange() {
    jedis.set("mykey", "This is a string");

    // Positive indices
    assertEquals("This", jedis.getrange("mykey", 0, 3));
    assertEquals("string", jedis.getrange("mykey", 10, 15));

    // Negative indices
    assertEquals("string", jedis.getrange("mykey", -6, -1));
    assertEquals("is", jedis.getrange("mykey", -11, -10));

    // Out of range
    assertEquals("string", jedis.getrange("mykey", 10, 100));
    assertEquals("", jedis.getrange("mykey", 20, 30));

    // Non-existing key
    assertEquals("", jedis.getrange("nonexistent", 0, 10));
}
```

---

## 16. SETRANGE - Set Substring

**Redis Source**: `redis/src/t_string.c:setrangeCommand()` (lines 442-505)

### Specification
```
SETRANGE key offset value
```
- Overwrites part of string starting at offset
- If offset > current length, pads with null bytes
- Returns: Length of string after modification

### Implementation

```java
private void setRange(final List<Object> list) {
    final String key = (String) list.get(1);
    final int offset = Integer.parseInt((String) list.get(2));
    final String replaceValue = (String) list.get(3);

    if (offset < 0) {
        throw new RedisException("ERR offset is out of range");
    }

    // Get existing value or create new
    String existing = "";
    if (keyExists(key)) {
        RedisType type = getKeyType(key);
        if (type != RedisType.STRING) {
            throw new RedisException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        existing = (String) defaultBucket.get(key);
    }

    // Calculate new length
    int newLength = Math.max(existing.length(), offset + replaceValue.length());

    // Build new string
    StringBuilder newValue = new StringBuilder(newLength);
    newValue.append(existing);

    // Pad with zeros if needed
    while (newValue.length() < offset) {
        newValue.append('\0');
    }

    // Replace substring
    if (offset < newValue.length()) {
        newValue.replace(offset, Math.min(offset + replaceValue.length(), newValue.length()),
                        replaceValue.substring(0, Math.min(replaceValue.length(), newValue.length() - offset)));
    }

    // Append remaining characters if needed
    if (offset + replaceValue.length() > newValue.length()) {
        newValue.append(replaceValue.substring(newValue.length() - offset));
    }

    defaultBucket.put(key, newValue.toString());
    respondValue(newValue.length(), false);
}
```

### Test Cases
```java
@Test
public void testSetRange() {
    jedis.set("mykey", "Hello World");

    // Replace part of string
    assertEquals(11L, jedis.setrange("mykey", 6, "Redis"));
    assertEquals("Hello Redis", jedis.get("mykey"));

    // Extend string
    assertEquals(15L, jedis.setrange("mykey", 11, " !!!"));
    assertEquals("Hello Redis !!!", jedis.get("mykey"));

    // Set with padding
    assertEquals(20L, jedis.setrange("key2", 10, "Hello"));
    assertEquals(20L, jedis.strlen("key2"));
}
```

---

## 17. MGET - Get Multiple Keys

**Redis Source**: `redis/src/t_string.c:mgetCommand()` (lines 548-564)

### Specification
```
MGET key [key ...]
```
- Returns: Array of values, nil for non-existing keys

### Implementation

```java
private void mGet(final List<Object> list) {
    value.append("*");
    value.append(list.size() - 1);

    for (int i = 1; i < list.size(); i++) {
        appendCrLf();
        final String key = (String) list.get(i);

        if (!keyExists(key)) {
            value.append("$-1");
        } else {
            RedisType type = getKeyType(key);
            if (type != RedisType.STRING) {
                value.append("$-1");
            } else {
                final String val = (String) defaultBucket.get(key);
                respondValue(val, true);
            }
        }
    }
}
```

### Redis Pattern
```c
// From redis/src/t_string.c
void mgetCommand(client *c) {
    int j;

    addReplyArrayLen(c,c->argc-1);
    for (j = 1; j < c->argc; j++) {
        robj *o = lookupKeyRead(c->db,c->argv[j]);
        if (o == NULL) {
            addReplyNull(c);
        } else {
            if (o->type != OBJ_STRING) {
                addReplyNull(c);
            } else {
                addReplyBulk(c,o);
            }
        }
    }
}
```

### Test Cases
```java
@Test
public void testMGet() {
    jedis.set("key1", "value1");
    jedis.set("key2", "value2");
    jedis.set("key3", "value3");

    List<String> values = jedis.mget("key1", "key2", "key3");
    assertEquals(Arrays.asList("value1", "value2", "value3"), values);

    // With non-existing keys
    values = jedis.mget("key1", "nonexistent", "key3");
    assertEquals(3, values.size());
    assertEquals("value1", values.get(0));
    assertNull(values.get(1));
    assertEquals("value3", values.get(2));
}
```

---

## 18. MSET - Set Multiple Keys

**Redis Source**: `redis/src/t_string.c:msetGenericCommand()` (lines 566-602)

### Specification
```
MSET key value [key value ...]
```
- Sets multiple key-value pairs
- Always returns OK
- Atomic operation

### Implementation

```java
private void mSet(final List<Object> list) {
    // Validate arguments (must be key-value pairs)
    if ((list.size() - 1) % 2 != 0) {
        throw new RedisException("ERR wrong number of arguments for MSET");
    }

    // Set all key-value pairs
    for (int i = 1; i < list.size(); i += 2) {
        final String key = (String) list.get(i);
        final String val = (String) list.get(i + 1);
        defaultBucket.put(key, val);
    }

    value.append("+OK");
}
```

### Redis Pattern
```c
// From redis/src/t_string.c
void msetGenericCommand(client *c, int nx) {
    int j;

    if ((c->argc % 2) == 0) {
        addReplyErrorArity(c);
        return;
    }

    /* Handle the NX flag */
    if (nx) {
        for (j = 1; j < c->argc; j += 2) {
            if (lookupKeyWrite(c->db,c->argv[j]) != NULL) {
                addReply(c, shared.czero);
                return;
            }
        }
    }

    for (j = 1; j < c->argc; j += 2) {
        c->argv[j+1] = tryObjectEncoding(c->argv[j+1]);
        setKey(c,c->db,c->argv[j],c->argv[j+1],0);
        notifyKeyspaceEvent(NOTIFY_STRING,"set",c->argv[j],c->db->id);
    }
    server.dirty += (c->argc-1)/2;
    addReply(c, nx ? shared.cone : shared.ok);
}
```

### Test Cases
```java
@Test
public void testMSet() {
    assertEquals("OK", jedis.mset("key1", "value1", "key2", "value2", "key3", "value3"));

    assertEquals("value1", jedis.get("key1"));
    assertEquals("value2", jedis.get("key2"));
    assertEquals("value3", jedis.get("key3"));

    // Overwrite existing
    jedis.mset("key1", "new1", "key2", "new2");
    assertEquals("new1", jedis.get("key1"));
    assertEquals("new2", jedis.get("key2"));
}
```

---

## 19. MSETNX - Set Multiple Keys If None Exist

**Redis Source**: `redis/src/t_string.c:msetGenericCommand()` with nx=1

### Specification
```
MSETNX key value [key value ...]
```
- Sets multiple keys only if NONE of them exist
- Returns: 1 if all set, 0 if at least one exists
- Atomic operation

### Implementation

```java
private void mSetNx(final List<Object> list) {
    // Validate arguments
    if ((list.size() - 1) % 2 != 0) {
        throw new RedisException("ERR wrong number of arguments for MSETNX");
    }

    // Check if ANY key exists
    for (int i = 1; i < list.size(); i += 2) {
        final String key = (String) list.get(i);
        if (keyExists(key)) {
            respondValue(0, false);
            return;
        }
    }

    // All keys don't exist, set them all
    for (int i = 1; i < list.size(); i += 2) {
        final String key = (String) list.get(i);
        final String val = (String) list.get(i + 1);
        defaultBucket.put(key, val);
    }

    respondValue(1, false);
}
```

### Test Cases
```java
@Test
public void testMSetNx() {
    // All keys don't exist
    assertEquals(1L, jedis.msetnx("key1", "value1", "key2", "value2"));
    assertEquals("value1", jedis.get("key1"));
    assertEquals("value2", jedis.get("key2"));

    // At least one key exists - none should be set
    assertEquals(0L, jedis.msetnx("key2", "new2", "key3", "value3"));
    assertEquals("value2", jedis.get("key2")); // unchanged
    assertNull(jedis.get("key3")); // not set
}
```

---

## 20. GETSET - Get and Set

**Redis Source**: `redis/src/t_string.c:getsetCommand()` (lines 430-440)

### Specification
```
GETSET key value
```
- Sets key to value and returns old value
- Returns: Old value, or nil if key didn't exist

### Implementation

```java
private void getSet(final List<Object> list) {
    final String key = (String) list.get(1);
    final String newValue = (String) list.get(2);

    // Check type if key exists
    if (keyExists(key)) {
        RedisType type = getKeyType(key);
        if (type != RedisType.STRING) {
            throw new RedisException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
    }

    // Get old value
    final String oldValue = (String) defaultBucket.get(key);

    // Set new value
    defaultBucket.put(key, newValue);

    // Return old value (or nil if didn't exist)
    if (oldValue == null) {
        value.append("$-1");
    } else {
        respondValue(oldValue, true);
    }
}
```

### Redis Pattern
```c
// From redis/src/t_string.c
void getsetCommand(client *c) {
    if (getGenericCommand(c) == C_ERR) return;
    c->argv[2] = tryObjectEncoding(c->argv[2]);
    setKey(c,c->db,c->argv[1],c->argv[2],0);
    notifyKeyspaceEvent(NOTIFY_STRING,"set",c->argv[1],c->db->id);
    server.dirty++;
}
```

### Test Cases
```java
@Test
public void testGetSet() {
    // Key doesn't exist
    assertNull(jedis.getSet("mykey", "value1"));
    assertEquals("value1", jedis.get("mykey"));

    // Key exists
    assertEquals("value1", jedis.getSet("mykey", "value2"));
    assertEquals("value2", jedis.get("mykey"));
}
```

---

## 21. SETEX - Set with Expiration (seconds)

**Redis Source**: `redis/src/t_string.c:setexCommand()` (lines 312-315)

### Specification
```
SETEX key seconds value
```
- Sets key with expiration in seconds
- Returns: OK

### Implementation

```java
private void setEx(final List<Object> list) {
    final String key = (String) list.get(1);
    final int seconds = Integer.parseInt((String) list.get(2));
    final String val = (String) list.get(3);

    if (seconds <= 0) {
        throw new RedisException("ERR invalid expire time in setex");
    }

    // Set value
    defaultBucket.put(key, val);

    // Set expiration
    final long expirationTime = System.currentTimeMillis() + (seconds * 1000L);
    expirationMap.put(key, expirationTime);

    value.append("+OK");
}
```

### Test Cases
```java
@Test
public void testSetEx() throws InterruptedException {
    assertEquals("OK", jedis.setex("mykey", 2, "value"));
    assertEquals("value", jedis.get("mykey"));
    assertTrue(jedis.ttl("mykey") > 0);

    Thread.sleep(2500);
    assertNull(jedis.get("mykey"));
}
```

---

## 22. PSETEX - Set with Expiration (milliseconds)

**Redis Source**: `redis/src/t_string.c:psetexCommand()` (lines 317-320)

### Specification
```
PSETEX key milliseconds value
```
- Sets key with expiration in milliseconds
- Returns: OK

### Implementation

```java
private void pSetEx(final List<Object> list) {
    final String key = (String) list.get(1);
    final int milliseconds = Integer.parseInt((String) list.get(2));
    final String val = (String) list.get(3);

    if (milliseconds <= 0) {
        throw new RedisException("ERR invalid expire time in psetex");
    }

    // Set value
    defaultBucket.put(key, val);

    // Set expiration
    final long expirationTime = System.currentTimeMillis() + milliseconds;
    expirationMap.put(key, expirationTime);

    value.append("+OK");
}
```

### Test Cases
```java
@Test
public void testPSetEx() throws InterruptedException {
    assertEquals("OK", jedis.psetex("mykey", 1500, "value"));
    assertEquals("value", jedis.get("mykey"));

    Thread.sleep(1600);
    assertNull(jedis.get("mykey"));
}
```

---

## 23. SETNX - Set If Not Exists

**Redis Source**: `redis/src/t_string.c:setnxCommand()` (lines 307-310)

### Specification
```
SETNX key value
```
- Sets key only if it doesn't exist
- Returns: 1 if set, 0 if key exists

### Implementation

```java
private void setNx(final List<Object> list) {
    final String key = (String) list.get(1);
    final String val = (String) list.get(2);

    if (keyExists(key)) {
        respondValue(0, false);
    } else {
        defaultBucket.put(key, val);
        respondValue(1, false);
    }
}
```

### Test Cases
```java
@Test
public void testSetNx() {
    // Key doesn't exist
    assertEquals(1L, jedis.setnx("mykey", "value"));
    assertEquals("value", jedis.get("mykey"));

    // Key exists
    assertEquals(0L, jedis.setnx("mykey", "newvalue"));
    assertEquals("value", jedis.get("mykey")); // unchanged
}
```

---

# Phase 3: List Commands

## 24. LPUSH - Push Elements to List Head

**Redis Source**: `redis/src/t_list.c:listTypePush()` (lines 146-169) and `pushGenericCommand()`

### Specification
```
LPUSH key element [element ...]
```
- Pushes elements to head (left) of list
- Creates list if it doesn't exist
- Returns: Length of list after push

### Implementation

```java
// Add storage for lists
private final Map<String, List<String>> listBucket = new ConcurrentHashMap<>();

private void lPush(final List<Object> list) {
    final String key = (String) list.get(1);

    // Check type if key exists
    if (keyExists(key)) {
        RedisType type = getKeyType(key);
        if (type != RedisType.LIST) {
            throw new RedisException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
    }

    // Get or create list
    List<String> redisLis = listBucket.computeIfAbsent(key, k -> new ArrayList<>());

    // Push elements to head (insert at beginning in reverse order)
    for (int i = list.size() - 1; i >= 2; i--) {
        redisLis.add(0, (String) list.get(i));
    }

    respondValue(redisLis.size(), false);
}
```

### Redis Pattern
```c
// From redis/src/t_list.c
void listTypePush(robj *subject, robj *value, int where) {
    if (subject->encoding == OBJ_ENCODING_QUICKLIST) {
        int pos = (where == LIST_HEAD) ? QUICKLIST_HEAD : QUICKLIST_TAIL;
        if (value->encoding == OBJ_ENCODING_INT) {
            char buf[32];
            ll2string(buf, 32, (long)value->ptr);
            quicklistPush(subject->ptr, buf, strlen(buf), pos);
        } else {
            quicklistPush(subject->ptr, value->ptr, sdslen(value->ptr), pos);
        }
    }
}

void pushGenericCommand(client *c, int where, int xx) {
    int j;
    robj *lobj = lookupKeyWrite(c->db, c->argv[1]);

    if (checkType(c,lobj,OBJ_LIST)) return;
    if (!lobj) {
        if (xx) {
            addReply(c, shared.czero);
            return;
        }
        lobj = createQuicklistObject();
        quicklistSetOptions(lobj->ptr, server.list_max_listpack_size,
                          server.list_compress_depth);
        dbAdd(c->db,c->argv[1],lobj);
    }

    for (j = 2; j < c->argc; j++) {
        listTypePush(lobj,c->argv[j],where);
    }

    addReplyLongLong(c, listTypeLength(lobj));
}
```

### Test Cases
```java
@Test
public void testLPush() {
    // Push to new list
    assertEquals(3L, jedis.lpush("mylist", "c", "b", "a"));

    // Verify order
    List<String> list = jedis.lrange("mylist", 0, -1);
    assertEquals(Arrays.asList("a", "b", "c"), list);

    // Push more elements
    assertEquals(5L, jedis.lpush("mylist", "z", "y"));
    list = jedis.lrange("mylist", 0, -1);
    assertEquals(Arrays.asList("y", "z", "a", "b", "c"), list);

    // Wrong type error
    jedis.set("string", "value");
    try {
        jedis.lpush("string", "element");
        fail("Should throw exception");
    } catch (JedisDataException e) {
        assertTrue(e.getMessage().contains("WRONGTYPE"));
    }
}
```

---

## 25. RPUSH - Push Elements to List Tail

**Redis Source**: `redis/src/t_list.c:listTypePush()` (lines 146-169)

### Specification
```
RPUSH key element [element ...]
```
- Pushes elements to tail (right) of list
- Creates list if it doesn't exist
- Returns: Length of list after push

### Implementation

```java
private void rPush(final List<Object> list) {
    final String key = (String) list.get(1);

    // Check type if key exists
    if (keyExists(key)) {
        RedisType type = getKeyType(key);
        if (type != RedisType.LIST) {
            throw new RedisException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
    }

    // Get or create list
    List<String> redisList = listBucket.computeIfAbsent(key, k -> new ArrayList<>());

    // Push elements to tail (append in order)
    for (int i = 2; i < list.size(); i++) {
        redisList.add((String) list.get(i));
    }

    respondValue(redisList.size(), false);
}
```

### Test Cases
```java
@Test
public void testRPush() {
    // Push to new list
    assertEquals(3L, jedis.rpush("mylist", "a", "b", "c"));

    // Verify order
    List<String> list = jedis.lrange("mylist", 0, -1);
    assertEquals(Arrays.asList("a", "b", "c"), list);

    // Push more elements
    assertEquals(5L, jedis.rpush("mylist", "d", "e"));
    list = jedis.lrange("mylist", 0, -1);
    assertEquals(Arrays.asList("a", "b", "c", "d", "e"), list);
}
```

---

## 26. LPOP - Pop Element from List Head

**Redis Source**: `redis/src/t_list.c:listTypePop()` (lines 175-202)

### Specification
```
LPOP key
```
- Removes and returns first element
- Returns: Element value, or nil if list is empty

### Implementation

```java
private void lPop(final List<Object> list) {
    final String key = (String) list.get(1);

    // Check if list exists
    if (!keyExists(key) || !listBucket.containsKey(key)) {
        value.append("$-1");
        return;
    }

    // Check type
    RedisType type = getKeyType(key);
    if (type != RedisType.LIST) {
        throw new RedisException("WRONGTYPE Operation against a key holding the wrong kind of value");
    }

    List<String> redisList = listBucket.get(key);

    if (redisList.isEmpty()) {
        value.append("$-1");
        return;
    }

    // Pop from head
    final String element = redisList.remove(0);

    // Clean up if list is empty
    if (redisList.isEmpty()) {
        listBucket.remove(key);
        expirationMap.remove(key);
    }

    respondValue(element, true);
}
```

### Redis Pattern
```c
// From redis/src/t_list.c
robj *listTypePop(robj *subject, int where) {
    robj *value = NULL;

    if (subject->encoding == OBJ_ENCODING_QUICKLIST) {
        long long vlong;
        int ql_where = where == LIST_HEAD ? QUICKLIST_HEAD : QUICKLIST_TAIL;
        if (quicklistPopCustom(subject->ptr, ql_where, (unsigned char **)&value,
                               NULL, &vlong, listPopSaver)) {
            if (!value)
                value = createStringObjectFromLongLong(vlong);
        }
    }
    return value;
}
```

### Test Cases
```java
@Test
public void testLPop() {
    jedis.rpush("mylist", "a", "b", "c");

    // Pop elements
    assertEquals("a", jedis.lpop("mylist"));
    assertEquals("b", jedis.lpop("mylist"));
    assertEquals("c", jedis.lpop("mylist"));

    // Empty list
    assertNull(jedis.lpop("mylist"));

    // Non-existing key
    assertNull(jedis.lpop("nonexistent"));
}
```

---

## 27. RPOP - Pop Element from List Tail

**Redis Source**: `redis/src/t_list.c:listTypePop()` (lines 175-202)

### Specification
```
RPOP key
```
- Removes and returns last element
- Returns: Element value, or nil if list is empty

### Implementation

```java
private void rPop(final List<Object> list) {
    final String key = (String) list.get(1);

    // Check if list exists
    if (!keyExists(key) || !listBucket.containsKey(key)) {
        value.append("$-1");
        return;
    }

    // Check type
    RedisType type = getKeyType(key);
    if (type != RedisType.LIST) {
        throw new RedisException("WRONGTYPE Operation against a key holding the wrong kind of value");
    }

    List<String> redisList = listBucket.get(key);

    if (redisList.isEmpty()) {
        value.append("$-1");
        return;
    }

    // Pop from tail
    final String element = redisList.remove(redisList.size() - 1);

    // Clean up if list is empty
    if (redisList.isEmpty()) {
        listBucket.remove(key);
        expirationMap.remove(key);
    }

    respondValue(element, true);
}
```

### Test Cases
```java
@Test
public void testRPop() {
    jedis.rpush("mylist", "a", "b", "c");

    // Pop elements
    assertEquals("c", jedis.rpop("mylist"));
    assertEquals("b", jedis.rpop("mylist"));
    assertEquals("a", jedis.rpop("mylist"));

    // Empty list
    assertNull(jedis.rpop("mylist"));
}
```

---

## 28. LLEN - Get List Length

**Redis Source**: `redis/src/t_list.c:listTypeLength()` (lines 204-212)

### Specification
```
LLEN key
```
- Returns: Length of list, or 0 if key doesn't exist

### Implementation

```java
private void lLen(final List<Object> list) {
    final String key = (String) list.get(1);

    // Check if key exists
    if (!keyExists(key) || !listBucket.containsKey(key)) {
        respondValue(0, false);
        return;
    }

    // Check type
    RedisType type = getKeyType(key);
    if (type != RedisType.LIST) {
        throw new RedisException("WRONGTYPE Operation against a key holding the wrong kind of value");
    }

    List<String> redisList = listBucket.get(key);
    respondValue(redisList.size(), false);
}
```

### Redis Pattern
```c
// From redis/src/t_list.c
unsigned long listTypeLength(const robj *subject) {
    if (subject->encoding == OBJ_ENCODING_QUICKLIST) {
        return quicklistCount(subject->ptr);
    } else if (subject->encoding == OBJ_ENCODING_LISTPACK) {
        return lpLength(subject->ptr);
    }
}
```

### Test Cases
```java
@Test
public void testLLen() {
    assertEquals(0L, jedis.llen("mylist"));

    jedis.rpush("mylist", "a", "b", "c");
    assertEquals(3L, jedis.llen("mylist"));

    jedis.lpop("mylist");
    assertEquals(2L, jedis.llen("mylist"));
}
```

---

## 29. LRANGE - Get List Range

**Redis Source**: `redis/src/t_list.c` - uses iterator pattern (lines 214-282)

### Specification
```
LRANGE key start stop
```
- Returns: Array of elements in range
- Negative indices count from end
- Out of range indices handled gracefully

### Implementation

```java
private void lRange(final List<Object> list) {
    final String key = (String) list.get(1);
    int start = Integer.parseInt((String) list.get(2));
    int stop = Integer.parseInt((String) list.get(3));

    // Check if list exists
    if (!keyExists(key) || !listBucket.containsKey(key)) {
        value.append("*0");
        return;
    }

    // Check type
    RedisType type = getKeyType(key);
    if (type != RedisType.LIST) {
        throw new RedisException("WRONGTYPE Operation against a key holding the wrong kind of value");
    }

    List<String> redisList = listBucket.get(key);
    int size = redisList.size();

    // Handle negative indices
    if (start < 0) start = size + start;
    if (stop < 0) stop = size + stop;

    // Clamp to valid range
    if (start < 0) start = 0;
    if (stop >= size) stop = size - 1;

    // Check if range is valid
    if (start > stop || start >= size) {
        value.append("*0");
        return;
    }

    // Build result
    List<String> result = redisList.subList(start, stop + 1);

    value.append("*");
    value.append(result.size());

    for (String element : result) {
        appendCrLf();
        respondValue(element, true);
    }
}
```

### Test Cases
```java
@Test
public void testLRange() {
    jedis.rpush("mylist", "a", "b", "c", "d", "e");

    // Full range
    assertEquals(Arrays.asList("a", "b", "c", "d", "e"),
                 jedis.lrange("mylist", 0, -1));

    // Partial range
    assertEquals(Arrays.asList("b", "c", "d"),
                 jedis.lrange("mylist", 1, 3));

    // Negative indices
    assertEquals(Arrays.asList("d", "e"),
                 jedis.lrange("mylist", -2, -1));

    // Out of range
    assertEquals(Arrays.asList("a", "b", "c", "d", "e"),
                 jedis.lrange("mylist", 0, 100));

    // Invalid range
    assertEquals(Collections.emptyList(),
                 jedis.lrange("mylist", 10, 20));
}
```

---

## 30. LINDEX - Get Element by Index

**Redis Source**: `redis/src/t_list.c:lindexCommand()`

### Specification
```
LINDEX key index
```
- Returns: Element at index, or nil if out of range
- Negative indices count from end

### Implementation

```java
private void lIndex(final List<Object> list) {
    final String key = (String) list.get(1);
    int index = Integer.parseInt((String) list.get(2));

    // Check if list exists
    if (!keyExists(key) || !listBucket.containsKey(key)) {
        value.append("$-1");
        return;
    }

    // Check type
    RedisType type = getKeyType(key);
    if (type != RedisType.LIST) {
        throw new RedisException("WRONGTYPE Operation against a key holding the wrong kind of value");
    }

    List<String> redisList = listBucket.get(key);
    int size = redisList.size();

    // Handle negative index
    if (index < 0) index = size + index;

    // Check bounds
    if (index < 0 || index >= size) {
        value.append("$-1");
        return;
    }

    respondValue(redisList.get(index), true);
}
```

### Test Cases
```java
@Test
public void testLIndex() {
    jedis.rpush("mylist", "a", "b", "c", "d", "e");

    // Positive indices
    assertEquals("a", jedis.lindex("mylist", 0));
    assertEquals("c", jedis.lindex("mylist", 2));
    assertEquals("e", jedis.lindex("mylist", 4));

    // Negative indices
    assertEquals("e", jedis.lindex("mylist", -1));
    assertEquals("d", jedis.lindex("mylist", -2));

    // Out of range
    assertNull(jedis.lindex("mylist", 10));
    assertNull(jedis.lindex("mylist", -10));
}
```

---

## 31. LSET - Set Element by Index

**Redis Source**: `redis/src/t_list.c:lsetCommand()`

### Specification
```
LSET key index element
```
- Sets element at index
- Error if index out of range
- Returns: OK

### Implementation

```java
private void lSet(final List<Object> list) {
    final String key = (String) list.get(1);
    int index = Integer.parseInt((String) list.get(2));
    final String element = (String) list.get(3);

    // Check if list exists
    if (!keyExists(key) || !listBucket.containsKey(key)) {
        throw new RedisException("ERR no such key");
    }

    // Check type
    RedisType type = getKeyType(key);
    if (type != RedisType.LIST) {
        throw new RedisException("WRONGTYPE Operation against a key holding the wrong kind of value");
    }

    List<String> redisList = listBucket.get(key);
    int size = redisList.size();

    // Handle negative index
    if (index < 0) index = size + index;

    // Check bounds
    if (index < 0 || index >= size) {
        throw new RedisException("ERR index out of range");
    }

    redisList.set(index, element);
    value.append("+OK");
}
```

### Test Cases
```java
@Test
public void testLSet() {
    jedis.rpush("mylist", "a", "b", "c");

    // Set element
    assertEquals("OK", jedis.lset("mylist", 1, "x"));
    assertEquals(Arrays.asList("a", "x", "c"),
                 jedis.lrange("mylist", 0, -1));

    // Negative index
    jedis.lset("mylist", -1, "z");
    assertEquals(Arrays.asList("a", "x", "z"),
                 jedis.lrange("mylist", 0, -1));

    // Out of range
    try {
        jedis.lset("mylist", 10, "y");
        fail("Should throw exception");
    } catch (JedisDataException e) {
        assertTrue(e.getMessage().contains("index out of range"));
    }
}
```

---

## 32. LINSERT - Insert Element Before/After

**Redis Source**: `redis/src/t_list.c:linsertCommand()`

### Specification
```
LINSERT key BEFORE|AFTER pivot element
```
- Inserts element before or after pivot
- Returns: Length after insert, or -1 if pivot not found

### Implementation

```java
private void lInsert(final List<Object> list) {
    final String key = (String) list.get(1);
    final String position = ((String) list.get(2)).toUpperCase();
    final String pivot = (String) list.get(3);
    final String element = (String) list.get(4);

    // Check if list exists
    if (!keyExists(key) || !listBucket.containsKey(key)) {
        respondValue(0, false);
        return;
    }

    // Check type
    RedisType type = getKeyType(key);
    if (type != RedisType.LIST) {
        throw new RedisException("WRONGTYPE Operation against a key holding the wrong kind of value");
    }

    List<String> redisList = listBucket.get(key);

    // Find pivot
    int pivotIndex = redisList.indexOf(pivot);
    if (pivotIndex == -1) {
        respondValue(-1, false);
        return;
    }

    // Insert before or after
    if ("BEFORE".equals(position)) {
        redisList.add(pivotIndex, element);
    } else if ("AFTER".equals(position)) {
        redisList.add(pivotIndex + 1, element);
    } else {
        throw new RedisException("ERR syntax error");
    }

    respondValue(redisList.size(), false);
}
```

### Test Cases
```java
@Test
public void testLInsert() {
    jedis.rpush("mylist", "a", "b", "d");

    // Insert before
    assertEquals(4L, jedis.linsert("mylist", ListPosition.BEFORE, "d", "c"));
    assertEquals(Arrays.asList("a", "b", "c", "d"),
                 jedis.lrange("mylist", 0, -1));

    // Insert after
    assertEquals(5L, jedis.linsert("mylist", ListPosition.AFTER, "a", "x"));
    assertEquals(Arrays.asList("a", "x", "b", "c", "d"),
                 jedis.lrange("mylist", 0, -1));

    // Pivot not found
    assertEquals(-1L, jedis.linsert("mylist", ListPosition.BEFORE, "z", "y"));
}
```

---

## 33. LREM - Remove Elements

**Redis Source**: `redis/src/t_list.c:lremCommand()`

### Specification
```
LREM key count element
```
- count > 0: Remove first count occurrences from head
- count < 0: Remove first |count| occurrences from tail
- count = 0: Remove all occurrences
- Returns: Number of removed elements

### Implementation

```java
private void lRem(final List<Object> list) {
    final String key = (String) list.get(1);
    int count = Integer.parseInt((String) list.get(2));
    final String element = (String) list.get(3);

    // Check if list exists
    if (!keyExists(key) || !listBucket.containsKey(key)) {
        respondValue(0, false);
        return;
    }

    // Check type
    RedisType type = getKeyType(key);
    if (type != RedisType.LIST) {
        throw new RedisException("WRONGTYPE Operation against a key holding the wrong kind of value");
    }

    List<String> redisList = listBucket.get(key);
    int removed = 0;

    if (count == 0) {
        // Remove all occurrences
        while (redisList.remove(element)) {
            removed++;
        }
    } else if (count > 0) {
        // Remove from head
        for (int i = 0; i < redisList.size() && removed < count; ) {
            if (redisList.get(i).equals(element)) {
                redisList.remove(i);
                removed++;
            } else {
                i++;
            }
        }
    } else {
        // Remove from tail
        int toRemove = -count;
        for (int i = redisList.size() - 1; i >= 0 && removed < toRemove; i--) {
            if (redisList.get(i).equals(element)) {
                redisList.remove(i);
                removed++;
            }
        }
    }

    // Clean up if list is empty
    if (redisList.isEmpty()) {
        listBucket.remove(key);
        expirationMap.remove(key);
    }

    respondValue(removed, false);
}
```

### Test Cases
```java
@Test
public void testLRem() {
    jedis.rpush("mylist", "a", "b", "a", "c", "a", "d", "a");

    // Remove first 2 'a' from head
    assertEquals(2L, jedis.lrem("mylist", 2, "a"));
    assertEquals(Arrays.asList("b", "c", "a", "d", "a"),
                 jedis.lrange("mylist", 0, -1));

    // Remove first 1 'a' from tail
    assertEquals(1L, jedis.lrem("mylist", -1, "a"));
    assertEquals(Arrays.asList("b", "c", "a", "d"),
                 jedis.lrange("mylist", 0, -1));

    // Remove all 'a'
    assertEquals(1L, jedis.lrem("mylist", 0, "a"));
    assertEquals(Arrays.asList("b", "c", "d"),
                 jedis.lrange("mylist", 0, -1));
}
```

---

## 34. LTRIM - Trim List to Range

**Redis Source**: `redis/src/t_list.c:ltrimCommand()`

### Specification
```
LTRIM key start stop
```
- Trims list to specified range
- Elements outside range are removed
- Returns: OK

### Implementation

```java
private void lTrim(final List<Object> list) {
    final String key = (String) list.get(1);
    int start = Integer.parseInt((String) list.get(2));
    int stop = Integer.parseInt((String) list.get(3));

    // Check if list exists
    if (!keyExists(key) || !listBucket.containsKey(key)) {
        value.append("+OK");
        return;
    }

    // Check type
    RedisType type = getKeyType(key);
    if (type != RedisType.LIST) {
        throw new RedisException("WRONGTYPE Operation against a key holding the wrong kind of value");
    }

    List<String> redisList = listBucket.get(key);
    int size = redisList.size();

    // Handle negative indices
    if (start < 0) start = size + start;
    if (stop < 0) stop = size + stop;

    // Clamp to valid range
    if (start < 0) start = 0;
    if (stop >= size) stop = size - 1;

    // If range is invalid, clear the list
    if (start > stop || start >= size) {
        listBucket.remove(key);
        expirationMap.remove(key);
    } else {
        // Keep only elements in range
        List<String> trimmed = new ArrayList<>(redisList.subList(start, stop + 1));
        listBucket.put(key, trimmed);
    }

    value.append("+OK");
}
```

### Test Cases
```java
@Test
public void testLTrim() {
    jedis.rpush("mylist", "a", "b", "c", "d", "e", "f");

    // Trim to middle range
    assertEquals("OK", jedis.ltrim("mylist", 1, 4));
    assertEquals(Arrays.asList("b", "c", "d", "e"),
                 jedis.lrange("mylist", 0, -1));

    // Trim with negative indices
    jedis.rpush("list2", "a", "b", "c", "d", "e");
    jedis.ltrim("list2", 0, -2);
    assertEquals(Arrays.asList("a", "b", "c", "d"),
                 jedis.lrange("list2", 0, -1));

    // Trim to empty
    jedis.ltrim("list2", 10, 20);
    assertEquals(0L, jedis.llen("list2"));
}
```

---

## 35. RPOPLPUSH - Pop from Source and Push to Destination

**Redis Source**: `redis/src/t_list.c:rpoplpushCommand()`

### Specification
```
RPOPLPUSH source destination
```
- Atomically pops from source tail and pushes to destination head
- Returns: Element moved, or nil if source is empty

### Implementation

```java
private void rPopLPush(final List<Object> list) {
    final String source = (String) list.get(1);
    final String destination = (String) list.get(2);

    // Check if source exists
    if (!keyExists(source) || !listBucket.containsKey(source)) {
        value.append("$-1");
        return;
    }

    // Check source type
    RedisType sourceType = getKeyType(source);
    if (sourceType != RedisType.LIST) {
        throw new RedisException("WRONGTYPE Operation against a key holding the wrong kind of value");
    }

    // Check destination type if it exists
    if (keyExists(destination) && listBucket.containsKey(destination)) {
        RedisType destType = getKeyType(destination);
        if (destType != RedisType.LIST) {
            throw new RedisException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
    }

    List<String> sourceList = listBucket.get(source);

    if (sourceList.isEmpty()) {
        value.append("$-1");
        return;
    }

    // Pop from source tail
    final String element = sourceList.remove(sourceList.size() - 1);

    // Clean up source if empty
    if (sourceList.isEmpty()) {
        listBucket.remove(source);
        expirationMap.remove(source);
    }

    // Push to destination head
    List<String> destList = listBucket.computeIfAbsent(destination, k -> new ArrayList<>());
    destList.add(0, element);

    respondValue(element, true);
}
```

### Test Cases
```java
@Test
public void testRPopLPush() {
    jedis.rpush("source", "a", "b", "c");
    jedis.rpush("dest", "x", "y");

    // Move element
    assertEquals("c", jedis.rpoplpush("source", "dest"));
    assertEquals(Arrays.asList("a", "b"),
                 jedis.lrange("source", 0, -1));
    assertEquals(Arrays.asList("c", "x", "y"),
                 jedis.lrange("dest", 0, -1));

    // Move to same list (rotate)
    jedis.del("mylist");
    jedis.rpush("mylist", "1", "2", "3");
    assertEquals("3", jedis.rpoplpush("mylist", "mylist"));
    assertEquals(Arrays.asList("3", "1", "2"),
                 jedis.lrange("mylist", 0, -1));

    // Empty source
    jedis.del("source");
    assertNull(jedis.rpoplpush("source", "dest"));
}
```

---

**Note**: BLPOP and BRPOP (blocking list operations) are excluded from this implementation as they require more complex server-side blocking/notification mechanisms that go beyond the basic data structure implementation scope.

---
