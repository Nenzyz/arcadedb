# Neo4j to ArcadeDB Migration Guide

This guide explains how to migrate from Neo4j to ArcadeDB using the Cypher query language support built on the ANTLR4 OpenCypher parser.

## Overview

ArcadeDB supports Cypher queries through an ANTLR4-based parser that translates Cypher to ArcadeDB's native SQL MATCH syntax. This enables Neo4j users to run most Cypher queries without modification.

### Supported Cypher Features

| Feature | Status | Notes |
|---------|--------|-------|
| MATCH (nodes, relationships) | Supported | Translates to ArcadeDB MATCH |
| WHERE (comparisons, logical ops, string ops) | Supported | Full operator support |
| RETURN (projections, aliases, DISTINCT) | Supported | Including aggregation functions |
| CREATE (nodes, relationships) | Supported | Via INSERT INTO / CREATE EDGE |
| DELETE / DETACH DELETE | Supported | Via DELETE VERTEX / DELETE EDGE |
| SET (properties, labels) | Supported | Via UPDATE statements |
| REMOVE (properties, labels) | Supported | Via UPDATE SET null |
| Variable-length paths | Supported | `*`, `*N`, `*N..M`, `*..M`, `*N..` |
| shortestPath / allShortestPaths | Supported | Native ArcadeDB algorithms |
| OPTIONAL MATCH | Supported | Via optional: true flag |
| ORDER BY, SKIP, LIMIT | Supported | Direct mapping |
| MERGE | Not supported | Throws clear error message |
| CALL procedures (APOC) | Not supported | Use ArcadeDB plugins |
| CREATE/DROP INDEX (Cypher syntax) | Not supported | Use ArcadeDB SQL |

## Step 1: Set Up Cypher-Compatible Types

For Neo4j-style multi-label nodes, create the CV (Cypher Vertex) and CE (Cypher Edge) types:

```sql
CREATE VERTEX TYPE CV;
CREATE PROPERTY CV.__labels__ LIST OF STRING;
CREATE INDEX ON CV (__labels__) UNIQUE;

CREATE EDGE TYPE CE;
CREATE PROPERTY CE.__labels__ LIST OF STRING;
CREATE INDEX ON CE (__labels__) UNIQUE;
```

**When to use CV/CE types vs. native types:**
- Use CV/CE when you need Neo4j-style multi-labeling (`(n:Person:Employee:Manager)`)
- Use native ArcadeDB types when you have a fixed type hierarchy and want maximum performance
- See [CV/CE Type Strategy](cypher-cv-ce-types.md) for detailed guidance

## Step 2: Import Data

### Option A: Using Cypher CREATE Statements

```cypher
CREATE (n:Person {name: 'John', age: 30})
CREATE (m:Person:Employee {name: 'Jane', age: 28, dept: 'Engineering'})
```

These translate to:
```sql
INSERT INTO CV SET __labels__ = ['Person'], name = 'John', age = 30
INSERT INTO CV SET __labels__ = ['Person', 'Employee'], name = 'Jane', age = 28, dept = 'Engineering'
```

### Option B: Bulk Import with ArcadeDB SQL

For large datasets, use ArcadeDB SQL directly for better performance:

```sql
INSERT INTO CV SET __labels__ = ['Person'], name = 'John', age = 30;
INSERT INTO CV SET __labels__ = ['Person'], name = 'Jane', age = 28;
CREATE EDGE KNOWS FROM (SELECT FROM CV WHERE name = 'John') TO (SELECT FROM CV WHERE name = 'Jane') SET since = 2020;
```

### Option C: Using the ArcadeDB Importer

ArcadeDB provides importers for various formats. Export your Neo4j data to CSV or JSON and use ArcadeDB's import tools.

## Step 3: Run Cypher Queries

### Via HTTP API

```bash
curl -X POST http://localhost:2480/api/v1/command/mydb \
  -H "Content-Type: application/json" \
  -d '{"language": "cypher", "command": "MATCH (n:Person) WHERE n.age > 25 RETURN n.name"}'
```

### Via Java API

```java
Database database = new DatabaseFactory("/path/to/db").open();
ResultSet result = database.query("cypher", "MATCH (n:Person) RETURN n.name");
while (result.hasNext()) {
    Result row = result.next();
    System.out.println(row.getProperty("n.name"));
}
```

## Query Translation Examples

### Basic MATCH

```cypher
-- Neo4j Cypher
MATCH (n:Person) WHERE n.age > 25 RETURN n.name, n.age ORDER BY n.age LIMIT 10

-- Translates to ArcadeDB SQL
MATCH {type: Person, as: n, where: (age > 25)} RETURN n.name, n.age ORDER BY n.age LIMIT 10
```

### Relationship Patterns

```cypher
-- Neo4j Cypher
MATCH (a:Person)-[:KNOWS]->(b:Person) WHERE a.name = 'John' RETURN b.name

-- Translates to ArcadeDB SQL
MATCH {type: Person, as: a, where: (name = 'John')}.out('KNOWS'){type: Person, as: b} RETURN b.name
```

### Variable-Length Paths

```cypher
-- Neo4j Cypher
MATCH (a:Person)-[:KNOWS*1..3]->(b:Person) RETURN a.name, b.name

-- Translates to ArcadeDB SQL (with depth constraint)
MATCH {type: Person, as: a}.out('KNOWS'){type: Person, as: b, while: ($depth <= 3)} RETURN a.name, b.name
```

### Shortest Path

```cypher
-- Neo4j Cypher
MATCH p = shortestPath((a:Person {name: 'John'})-[:KNOWS*]-(b:Person {name: 'Jane'}))
RETURN p

-- Translates to ArcadeDB SQL
SELECT shortestPath(
  (SELECT FROM CV WHERE __labels__ CONTAINS 'Person' AND name = 'John'),
  (SELECT FROM CV WHERE __labels__ CONTAINS 'Person' AND name = 'Jane'),
  'BOTH', 'KNOWS', 'maxDepth', 10
) AS p
```

### OPTIONAL MATCH

```cypher
-- Neo4j Cypher
MATCH (a:Person)
OPTIONAL MATCH (a)-[:KNOWS]->(b:Person)
RETURN a.name, b.name

-- Translates to ArcadeDB MATCH with optional: true
MATCH {type: Person, as: a}, {type: Person, as: b, optional: true} RETURN a.name, b.name
```

## Known Architectural Differences

### Transaction Model
- Neo4j uses optimistic locking with multiple isolation levels
- ArcadeDB uses MVCC with its own isolation guarantees
- Concurrent write patterns may behave differently

### Type System
- Neo4j: Nodes have mutable label sets; labels are independent
- ArcadeDB: Elements have a single immutable type with optional type inheritance
- The CV/CE strategy bridges this gap using `__labels__` property arrays

### Index Capabilities
- Neo4j composite indexes map differently to ArcadeDB LSM-Tree indexes
- Neo4j uniqueness constraints translate to ArcadeDB UNIQUE indexes
- Full-text search syntax differs (ArcadeDB uses Lucene integration)

### Temporal and Spatial Types
- Neo4j temporal types (Date, DateTime, Duration) may have precision differences
- Neo4j spatial types (Point, distance()) require ArcadeDB geospatial APIs

### Procedures and Functions
- Neo4j APOC procedures are not available in ArcadeDB
- Standard Cypher functions (count, sum, avg, min, max) are supported
- Custom functions require ArcadeDB plugin implementation

## Performance Tuning Tips

1. **Use native types for single-label patterns**: When your data model has fixed types, use native ArcadeDB types instead of CV/CE for better performance
2. **Index frequently queried properties**: Create indexes on properties used in WHERE clauses
3. **Bound variable-length paths**: Always specify upper bounds (e.g., `*1..5` instead of `*`) to prevent expensive unbounded traversals. Default max is 10 hops.
4. **Use query caching**: ArcadeDB caches translated queries via `CYPHER_STATEMENT_CACHE` configuration
5. **Prefer ArcadeDB SQL for complex operations**: For bulk operations, ArcadeDB SQL may be more efficient than Cypher translation

## CypherQueryEngine API

The `CypherQueryEngine` is registered automatically when the gremlin module is available. It processes Cypher queries through:

1. **Parsing**: ANTLR4 OpenCypher grammar validates syntax
2. **Component Extraction**: Visitor pattern extracts MATCH, WHERE, RETURN, etc.
3. **Translation**: Specialized translators convert each component to ArcadeDB SQL
4. **Execution**: Translated SQL executes via ArcadeDB's native query engine

### Configuration

| Setting | Default | Description |
|---------|---------|-------------|
| `arcadedb.cypher.statementCache` | 1000 | Number of translated statements to cache |

### Error Handling

Cypher syntax errors produce clear messages with line/column position:
```
Cypher syntax error at line 1, column 15: mismatched input 'WHER' expecting ...
```

Unsupported features produce explicit error messages:
```
MERGE query translation not yet implemented
```
