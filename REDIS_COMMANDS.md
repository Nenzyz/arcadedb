# Redis 1.0 (Released 2009) - The Foundation
This version established the core data structures and fundamental operations. All commands here were "new."

Key-Value Store Basics:

GET, SET, DEL, EXISTS, TYPE, EXPIRE, TTL, PERSIST (though PERSIST was refined later)
KEYS, RANDOMKEY, RENAME, RENAMENX, DBSIZE, FLUSHDB, FLUSHALL
Strings:

APPEND, GETRANGE, SETRANGE, GETSET, INCR, INCRBY, DECR, DECRBY, MGET, MSET, MSETNX, SETEX, PSETEX, SETNX, STRLEN
Lists:

LPUSH, RPUSH, LPOP, RPOP, LINDEX, LLEN, LINSERT, LREM, LSET, LTRIM, RPOPLPUSH, BLPOP, BRPOP, BRPOPLPUSH
Sets:

SADD, SREM, SCARD, SISMEMBER, SMEMBERS, SRANDMEMBER, SPOP, SDIFF, SDIFFSTORE, SINTER, SINTERSTORE, SUNION, SUNIONSTORE
Hashes:

HGET, HSET, HDEL, HEXISTS, HGETALL, HINCRBY, HMGET, HMSET, HSETNX, HVALS, HLEN
Sorted Sets:

ZADD, ZREM, ZCARD, ZINCRBY, ZINTERSTORE, ZUNIONSTORE, ZRANGE, ZRANGEBYSCORE, ZRANK, ZREMRANGEBYRANK, ZREMRANGEBYSCORE, ZREVRANGE, ZREVRANGEBYSCORE, ZREVRANK, ZSCORE
Pub/Sub:

PUBLISH, SUBSCRIBE, UNSUBSCRIBE, PSUBSCRIBE, PUNSUBSCRIBE
Transactions:

MULTI, EXEC, DISCARD, WATCH, UNWATCH
Connection & Server:

AUTH, ECHO, PING, QUIT, SELECT
BGREWRITEAOF, BGSAVE, INFO, LASTSAVE, SAVE, SHUTDOWN, SLAVEOF


# Redis 2.0 (Released 2010)

Focused on stability, performance, and some key data type enhancements.

Strings:

GETBIT, SETBIT, BITCOUNT, BITOP (Bitwise operations)
INCRBYFLOAT
Hashes:

HINCRBYFLOAT
Sorted Sets:

ZCOUNT, ZREMRANGEBYRANK, ZREMRANGEBYSCORE (These were present in 1.x but often refined in 2.x)
Server:

CONFIG GET, CONFIG SET, CLIENT SETNAME, CLIENT GETNAME

# Redis 2.2 (Released 2010)

Introduced more advanced key management and debugging.

Keys:

EXPIREAT, PEXPIREAT (Set expiration with Unix timestamp)
PERSIST (Explicitly remove expiration)
Server:

DEBUG (Various debugging commands)
OBJECT (Inspect internal object details)
MONITOR (Stream all commands processed by the server)
SYNC (Internal replication command)

# Redis 2.4 (Released 2011)

A significant release, bringing Lua scripting and advanced data migration.

Scripting:

EVAL, EVALSHA (Lua scripting engine)
SCRIPT LOAD, SCRIPT EXISTS, SCRIPT FLUSH, SCRIPT KILL
Keys:

MIGRATE (Atomically move a key from one Redis instance to another)
DUMP, RESTORE (Serialize/deserialize a key's value)
Server:

TIME (Return the current server time)
CLIENT KILL, CLIENT LIST
CONFIG REWRITE (Rewrite the config file with the current configuration)

# Redis 2.6 (Released 2012)

Introduced HyperLogLog, improved iteration, and more precise sorted set operations.

HyperLogLog:

PFADD, PFCOUNT, PFMERGE (Probabilistic data structure for unique element counting)
Iteration:

SCAN, SSCAN, HSCAN, ZSCAN (Cursor-based iteration for keys, sets, hashes, sorted sets)
Strings:

BITPOS (Find the first bit set or cleared in a string)
Sorted Sets:

ZLEXCOUNT, ZRANGEBYLEX, ZREMRANGEBYLEX (Lexicographical range operations)
Pub/Sub:

PUBSUB (Inspect Pub/Sub channels and subscriptions)
Server:

WAIT (Block until a command has been replicated to N replicas)

# Redis 2.8 (Released 2013)

Focused on operational improvements and diagnostics.

Server:

ROLE (Report the role of a Redis instance: master, slave, or sentinel)
CLIENT PAUSE (Stop processing commands from clients for a given amount of time)
CONFIG RESETSTAT (Reset the statistics reported by INFO)
SLOWLOG (Manage the slow queries log)
Redis 3.0 (Released 2015)
The landmark release that introduced Redis Cluster. Also brought Geospatial indexing.

Redis Cluster:

While many cluster commands are internal or for administration (CLUSTER ADDSLOTS, CLUSTER FORGET, CLUSTER MEET, CLUSTER NODES, CLUSTER REPLICATE, CLUSTER SETSLOT, etc.), the core data commands remain the same, but clients become cluster-aware.
Geospatial:

GEOADD, GEODIST, GEOHASH, GEOPOS, GEORADIUS, GEORADIUSBYMEMBER (Store and query geographical coordinates)
Strings:

BITFIELD (Arbitrary bitfield operations)
Sets:

SRANDMEMBER (Enhanced with count argument)
Sorted Sets:

ZADD (Enhanced with NX, XX, CH, INCR options)

# Redis 3.2 (Released 2016)

Minor enhancements and utility commands.

Sets:

SPOP (Enhanced with count argument)
Keys:

SWAPDB (Atomically swap two Redis databases)
Server:

LOLWUT (A fun, non-functional command, later removed in 7.0)
Redis 4.0 (Released 2017)
Introduced the Modules API, allowing for custom data types and commands. Also brought new eviction policies and some blocking list/sorted set commands.

Keys:

UNLINK (Asynchronous DEL)
MEMORY (Report memory usage of a key or the server)
Lists:

RPOPLPUSH (Enhanced with timeout argument for blocking)
Sorted Sets:

ZPOPMAX, ZPOPMIN (Pop elements with highest/lowest scores)
BZPOPMAX, BZPOPMIN (Blocking versions of ZPOPMAX/ZPOPMIN)
Streams (Initial Commands):

XADD, XTRIM, XDEL, XRANGE, XREVRANGE, XLEN, XREAD (Initial set of commands for the Streams data type)
Server:

LATENCY (Check and monitor latency events)
CLIENT REPLY (Control the server's reply behavior)

# Redis 5.0 (Released 2018)

Major release with the full Streams API, improved ACLs (initial), and more.

Streams (Full API):

XGROUP, XREADGROUP, XACK, XPENDING, XCLAIM, XINFO (Consumer groups, message acknowledgment, pending entries, stream introspection)
Lists:

LPOP, RPOP (Enhanced with count argument)
Sets:

SMISMEMBER (Check membership of multiple members in a set)
Server:

CLIENT ID, CLIENT UNBLOCK
ACL (Initial version of Access Control List commands: ACL CAT, ACL DELUSER, ACL GETUSER, ACL LIST, ACL LOAD, ACL LOG, ACL SAVE, ACL SETUSER, ACL USERS, ACL WHOAMI)

# Redis 6.0 (Released 2020)

Focused on security (ACLs, TLS), performance (IO threads), and client-side caching.

ACL (Major Overhaul):

Significant enhancements and new options for existing ACL commands.
Client-Side Caching:

CLIENT TRACKING, CLIENT TRACKINGINFO, CLIENT GETREDIR (Commands for managing client-side caching)
Streams:

XAUTOCLAIM (Automatically claim pending messages from a consumer group)
XTRIM (Enhanced with MINID option)
Server:

HELLO (Negotiate RESP protocol version)
COMMAND (Enhanced with COMMAND GETKEYS, COMMAND INFO for introspection)
CONFIG SET (Enhanced with REWRITE option)

# Redis 6.2 (Released 2021)

Introduced new list and hash commands, and improved sorted set functionality.

Keys:

COPY (Copy a key to a different destination database)
GETDEL (Get the value of a key and delete it)
SET (Enhanced with GET option to return old value)
Lists:

LPOS (Find the position of an element in a list)
Hashes:

HRANDFIELD (Get random fields from a hash)
Sorted Sets:

ZMSCORE (Get scores of multiple members in a sorted set)
Server-Side Functions (Initial):

FUNCTION LOAD, FUNCTION DELETE, FUNCTION LIST, FUNCTION DUMP, FUNCTION RESTORE, FUNCTION FLUSH (Initial commands for server-side functions, a more powerful and secure alternative to Lua scripting for some use cases)

# Redis 7.0 (Released 2022)

Focused on improved functions, set/sorted set operations, and better introspection.

Server-Side Functions (Improvements):

FCALL, FCALL_RO (Execute server-side functions)
Keys:

EXPIRETIME, PEXPIRETIME (Return the expiration time of a key as a Unix timestamp)
Sets:

SINTERCARD, SUNIONCARD, SDIFFCARD (Return the cardinality of set intersections/unions/differences without storing the result)
Sorted Sets:

ZDIFF, ZINTER, ZUNION (Return the difference/intersection/union of sorted sets without storing the result)
ZDIFFSTORE, ZINTERSTORE, ZUNIONSTORE (Enhanced with WITHSCORES option)
Server:

COMMAND DOCS (Get documentation for Redis commands)
LOLWUT (Removed)

# Redis 7.2 (Released 2023)

A significant release, making RedisJSON a built-in data type and adding more list and hash commands.

JSON (Built-in):

JSON.SET, JSON.GET, JSON.DEL, JSON.TYPE, JSON.NUMINCRBY, JSON.NUMMULTBY, JSON.STRAPPEND, JSON.STRLEN, JSON.ARRAPPEND, JSON.ARRINDEX, JSON.ARRINSERT, JSON.ARRLEN, JSON.ARRPOP, JSON.ARRTRIM, JSON.OBJKEYS, JSON.OBJLEN, JSON.TOGGLE, JSON.CLEAR (These commands, previously available via the RedisJSON module, are now built into core Redis)
Keys:

EXPIRE, PEXPIRE, EXPIREAT, PEXPIREAT (Enhanced with NX, XX, GT, LT options for conditional expiration)
SET (Enhanced with KEEPTTL option)
Lists:

LMOVE, BLMOVE (Atomically move an element from one list to another, blocking version included)
Hashes:

HRANDFIELD (Enhanced with WITHVALUES option)
Sorted Sets:

ZADDCARD (Add members to a sorted set and return the number of new elements added)
Cluster:

CLUSTER SHARDS, CLUSTER LINKS (New commands for cluster introspection)
