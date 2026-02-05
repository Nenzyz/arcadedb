# AGENTS.md

This document provides AI agents with essential information about the ArcadeDB project to facilitate effective code assistance and development tasks.

## Project Description

**ArcadeDB** is a high-performance, open-source Multi-Model Database Management System (DBMS) designed for extreme performance. Originally forked from the OrientDB Open Source project after its acquisition by SAP, ArcadeDB features a completely rewritten engine built with "Alien Technology" - low-level Java optimizations that enable processing millions of records per second on common hardware while using minimal resources.

### Core Philosophy

ArcadeDB is written in **LLJ (Low Level Java)** - it uses Java 21+ but leverages low-level APIs to achieve advanced mechanical sympathy techniques and reduce garbage collection pressure. The system is highly optimized for extreme performance and can run from a Raspberry Pi to multiple cloud servers.

### Key Characteristics

- **Multi-Model Database**: Supports Graph, Document, Key/Value, Search Engine, Time Series, and Vector Embedding models in a single database
- **ACID Compliant**: Fully transactional with complete ACID transaction support
- **High Performance**: Optimized for extreme performance with minimal resource usage
- **Production Ready**: Cloud-ready with Docker and Kubernetes support
- **Open Source**: Licensed under Apache 2.0

## Supported Data Models

1. **Graph Database**: Native graph engine with links between records (no joins needed)
   - Compatible with Neo4j Cypher, Apache Tinkerpop Gremlin, and OrientDB SQL
2. **Document Database**: Document-oriented storage
   - Compatible with MongoDB driver + MongoDB queries and OrientDB SQL
3. **Key/Value**: Simple key-value storage
   - Compatible with Redis driver (subset of operations)
4. **Search Engine**: Full-text search capabilities
   - Lucene-based full-text indexing
5. **Time Series**: Optimized for time-series data
6. **Vector Embedding**: Vector similarity search
   - Distance calculations for embeddings

## Query Languages

ArcadeDB supports multiple query languages, allowing developers to use their preferred syntax:

- **SQL**: OrientDB-compatible SQL
- **Cypher**: OpenCypher (Neo4j-compatible)
- **Gremlin**: Apache Tinkerpop v3.7.x
- **GraphQL**: GraphQL query language
- **MongoDB Query Language**: MongoDB-compatible queries

## Wire Protocols & Access Methods

ArcadeDB can be accessed through multiple protocols:

- **Embedded**: Direct JVM integration from any JVM language
- **HTTP/JSON**: RESTful API over HTTP
- **PostgreSQL Wire Protocol**: Use any PostgreSQL driver
- **Redis Protocol**: Redis-compatible commands (subset)
- **MongoDB Protocol**: MongoDB-compatible operations (subset)
- **gRPC**: High-performance RPC protocol

## Technology Stack

### Core Technologies
- **Language**: Java 21+ (Java 17 supported on `java17` branch)
- **Build System**: Maven (multi-module project)
- **Frontend**: Node.js 18.12.0+ (for Studio web interface)
- **Storage**: Custom LSM-Tree based storage engine
- **Indexing**: LSM-Tree indexes, Lucene (full-text), Vector indexes

### Key Design Principles
- **Low-Level Java**: Direct memory management, minimal GC pressure
- **Thread-Safe**: All implementations are thread-safe
- **Mechanical Sympathy**: Optimized for hardware characteristics
- **Zero-Copy**: Where possible, avoids unnecessary data copying

## Project Structure

### Core Modules

- **`engine/`**: Core database engine
  - Storage engine, indexing, query execution
  - Database lifecycle, transactions, ACID compliance
  - Schema management, serialization
  - Graph engine (vertices/edges, traversals)

- **`server/`**: Server implementation
  - HTTP/REST API endpoints
  - WebSocket support
  - High Availability (clustering, replication, leader election)
  - Security (authentication, authorization)
  - Monitoring (metrics, health checks)
  - Event handling

- **`network/`**: Network communication layer
  - Protocol implementations
  - Connection management

- **`studio/`**: Web-based administration interface
  - JavaScript/Node.js frontend
  - Database management UI
  - Query interface

- **`console/`**: Command-line console application
  - Interactive database console
  - Script execution

### Wire Protocol Modules

- **`gremlin/`**: Apache Tinkerpop Gremlin support
- **`mongodbw/`**: MongoDB wire protocol compatibility
- **`redisw/`**: Redis wire protocol compatibility
- **`graphql/`**: GraphQL API support
- **`postgresw/`**: PostgreSQL wire protocol compatibility
- **`grpc/`**: gRPC protocol definitions
- **`grpcw/`**: gRPC wire protocol implementation
- **`grpc-client/`**: gRPC client library

### Supporting Modules

- **`metrics/`**: Metrics and monitoring
- **`integration/`**: Integration tests
- **`e2e/`**: End-to-end tests
- **`e2e-perf/`**: Performance tests
- **`e2e-studio/`**: Studio UI end-to-end tests
- **`e2e-python/`**: Python binding tests
- **`package/`**: Distribution packaging and scripts
- **`coverage/`**: Code coverage reports
- **`test-utils/`**: Testing utilities

### External Components

- **`bindings/python/`**: Python client bindings
- **`neo4j-testkit/`**: Neo4j compatibility testing tools
- **`k8s/`**: Kubernetes deployment configurations

## Package Variants

Three release variants are available:

1. **`full`**: Complete package with all modules
2. **`minimal`**: Excludes `gremlin`, `redisw`, `mongodbw`, `graphql` modules
3. **`headless`**: Excludes `gremlin`, `redisw`, `mongodbw`, `graphql`, `studio` modules

## Development Environment

### Prerequisites
- **Java**: 21+ (main branch) or 17 (java17 branch)
- **Maven**: For building Java modules
- **Node.js**: >=18.12.0, npm >=8.0.0 (for Studio frontend)

### Build Commands

**Java (Maven)**:
- Full build: `mvn clean install`
- Build without tests: `mvn clean install -DskipTests`
- Run tests: `mvn test`
- Integration tests: `mvn clean install -P integration`
- Coverage: `mvn clean install -P coverage`

**Frontend (Studio)**:
- Build: `cd studio && npm run build`
- Development: `cd studio && npm run dev`
- Security audit: `cd studio && npm run security-audit`

### JVM Arguments

Tests and server require specific JVM exports:
```
--add-exports java.management/sun.management=ALL-UNNAMED
--add-opens java.base/java.util.concurrent.atomic=ALL-UNNAMED
--add-opens java.base/java.nio.channels.spi=ALL-UNNAMED
```

## Key Architecture Components

### Database Engine (`com.arcadedb.*`)
- **Database Management**: `com.arcadedb.database.*` - Lifecycle, transactions, ACID
- **Storage Engine**: `com.arcadedb.engine.*` - Low-level storage, page management, WAL
- **Query Processing**: `com.arcadedb.query.sql.*` - SQL parsing, execution planning
- **Schema Management**: `com.arcadedb.schema.*` - Type definitions, properties
- **Index System**: `com.arcadedb.index.*` - LSM-Tree, full-text, vector indexes
- **Graph Engine**: `com.arcadedb.graph.*` - Vertices/edges, traversals
- **Serialization**: `com.arcadedb.serializer.*` - Binary serialization, JSON

### Server Components (`com.arcadedb.server.*`)
- **HTTP API**: `com.arcadedb.server.http.*` - REST endpoints
- **High Availability**: `com.arcadedb.server.ha.*` - Clustering, replication
- **Security**: `com.arcadedb.server.security.*` - AuthN/AuthZ
- **Monitoring**: `com.arcadedb.server.monitor.*` - Metrics, health checks
- **Events**: `com.arcadedb.server.event.*` - Event handling

## Important Conventions

### Wire Protocol Module Dependencies
- All wire protocol modules must use `provided` scope for `arcadedb-server` dependency
- Protocol modules should NOT depend on other protocol modules in compile scope
- Only the server assembly (package module) can aggregate protocol modules
- Cross-module test dependencies should use `test` scope only

### Testing
- Unit tests: `src/test/java` in each module
- Integration tests: Suffix with `IT` (run with `-P integration`)
- Performance tests: In `performance/` packages and `e2e-perf/` module
- Test databases: Isolated in `databases/` directory
- Use TestContainers for external service testing

### Code Quality
- Pre-commit hooks: Uses [pre-commit](https://pre-commit.com/) for code quality checks
- License: All code must comply with Apache 2.0 license
- Security: Report issues to support@arcadedata.com (not public GitHub)

## Distribution

After building, the distribution is located at:
```
package/target/arcadedb-<version>.dir/arcadedb-<version>/
```

Server scripts are available in:
- Unix: `package/src/main/scripts/server.sh`
- Windows: `package/src/main/scripts/server.bat`

## Quick Start

Start ArcadeDB Server with Docker:
```bash
docker run --rm -p 2480:2480 -p 2424:2424 \
           -e JAVA_OPTS="-Darcadedb.server.rootPassword=playwithdata" \
           arcadedata/arcadedb:latest
```

Access Studio at: http://localhost:2480

## Additional Resources

- **Documentation**: https://docs.arcadedb.com
- **Community**: Discord, Twitter, LinkedIn, Stack Overflow
- **Security Issues**: support@arcadedb.com
- **Contributing**: See CONTRIBUTING.md

## For AI Agents

When working with this codebase:

1. **Performance is Critical**: Always consider performance implications of changes
2. **Thread Safety**: Ensure all modifications maintain thread safety
3. **Low-Level Optimizations**: Respect the LLJ philosophy - avoid unnecessary object creation
4. **Module Boundaries**: Understand wire protocol module dependency rules
5. **Testing**: Include appropriate tests (unit, integration, or performance)
6. **Documentation**: Update relevant documentation when making significant changes
7. **Compatibility**: Maintain backward compatibility with existing query languages and protocols
