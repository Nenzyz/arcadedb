# CV/CE Type Strategy for Cypher Compatibility

## Overview

ArcadeDB uses two special vertex and edge types -- CV (Cypher Vertex) and CE (Cypher Edge) -- to support Neo4j-style multi-labeling and dynamic label mutation. This document explains the strategy, when to use it, and the performance trade-offs.

## The Problem

Neo4j and ArcadeDB have fundamentally different type systems:

| Feature | Neo4j | ArcadeDB (Native) |
|---------|-------|--------------------|
| Labels per node | Multiple (set-based) | Single type |
| Label mutation | SET/REMOVE at runtime | Type is immutable |
| Type hierarchy | Flat labels | Inheritance hierarchy |
| Schema | Optional per label | Optional per type |

## The Solution: CV and CE Types

CV (Cypher Vertex) and CE (Cypher Edge) are schemaless types that store labels as a property array:

```sql
-- Create the types (one-time setup)
CREATE VERTEX TYPE CV;
CREATE PROPERTY CV.__labels__ LIST OF STRING;
CREATE INDEX ON CV (__labels__) UNIQUE;

CREATE EDGE TYPE CE;
CREATE PROPERTY CE.__labels__ LIST OF STRING;
CREATE INDEX ON CE (__labels__) UNIQUE;
```

### How Labels Are Stored

```
Neo4j node:  (:Person:Employee:Manager {name: 'John'})

ArcadeDB CV: type=CV, __labels__=['Person','Employee','Manager'], name='John'
```

### How Label Queries Work

```cypher
-- Cypher: single label match
MATCH (n:Person) RETURN n
-- ArcadeDB: WHERE __labels__ CONTAINS 'Person'

-- Cypher: multi-label match
MATCH (n:Person:Employee) RETURN n
-- ArcadeDB: WHERE __labels__ CONTAINSALL ['Person', 'Employee']
```

### How Label Mutations Work

```cypher
-- Cypher: add label
MATCH (n:Person {name: 'John'}) SET n:Manager
-- ArcadeDB: UPDATE CV SET __labels__ = __labels__.append('Manager')
--           WHERE __labels__ CONTAINS 'Person' AND name = 'John'
--           AND NOT (__labels__ CONTAINS 'Manager')

-- Cypher: remove label
MATCH (n:Manager) REMOVE n:Manager
-- ArcadeDB: UPDATE CV SET __labels__ = __labels__.remove('Manager')
--           WHERE __labels__ CONTAINS 'Manager'
```

## When to Use CV/CE vs. Native Types

### Use CV/CE Types When:

1. **Migrating from Neo4j** and your data uses multi-labeling
2. **Nodes need multiple labels**: `(:Person:Employee:Manager)`
3. **Labels change at runtime**: `SET n:Manager`, `REMOVE n:Employee`
4. **Schema is flexible**: Properties vary across nodes of the same "type"
5. **Rapid prototyping**: You want Neo4j-like flexibility without defining types upfront

### Use Native ArcadeDB Types When:

1. **Fixed type hierarchy**: Your data model has well-defined types that don't change
2. **Maximum query performance**: Native type lookups are faster than property-based label queries
3. **Type inheritance**: You want `Manager extends Employee extends Person`
4. **Schema validation**: You want strict property type checking
5. **Single-label patterns**: Most queries use single labels (the translator uses native types automatically for single-label patterns)

### Hybrid Approach (Recommended for Most Migrations)

The Cypher translator uses a **hybrid strategy** automatically:
- **Single-label patterns** (`(n:Person)`) use **native ArcadeDB types** if the type exists in the schema
- **Multi-label patterns** (`(n:Person:Employee)`) use **CV type** with `__labels__` property

This means you can:
1. Create native types for your primary types: `CREATE VERTEX TYPE Person`
2. Use CV only for nodes that need multi-labeling
3. Get the best of both worlds: native performance for simple queries, Neo4j compatibility for complex patterns

## Storage Overhead

| Component | Size | Notes |
|-----------|------|-------|
| Type name (CV) | 2 bytes | Stored implicitly via bucket ID |
| `__labels__` property | 8-64 bytes | Depends on label count (avg 3 labels = ~30 bytes) |
| Total overhead per vertex | ~30-70 bytes | For full Neo4j label compatibility |

At 1 billion vertices with an average of 3 labels: approximately 30-70 GB additional storage compared to native types.

## Performance Characteristics

| Operation | Complexity | Notes |
|-----------|------------|-------|
| Label query (single) | O(log n) | Via LSM-Tree index on `__labels__` |
| Label query (multi) | O(log n) | CONTAINSALL uses index |
| Label mutation (add) | O(1) amortized | Property array append |
| Label mutation (remove) | O(k) | k = number of labels on node |
| Property access | O(1) | Direct access for schemaless properties |

### Comparison: CV/CE vs. Native Types

| Query | Native Type | CV Type | Overhead |
|-------|-------------|---------|----------|
| Match by type | Direct bucket scan | Index lookup on `__labels__` | ~2-5x slower |
| Property access | Same | Same | None |
| Create vertex | Same | Plus `__labels__` write | Minimal |
| Traversal | Same | Same | None |

## Schema Flexibility Modes

### 1. Fully Schemaless (Maximum Flexibility)

```sql
CREATE VERTEX TYPE CV;
CREATE PROPERTY CV.__labels__ LIST OF STRING;
CREATE INDEX ON CV (__labels__) UNIQUE;
-- All other properties stored schemaless
```

### 2. Hybrid (Recommended)

```sql
CREATE VERTEX TYPE CV;
CREATE PROPERTY CV.__labels__ LIST OF STRING;
CREATE PROPERTY CV.name STRING;      -- Indexed, validated
CREATE PROPERTY CV.email STRING;     -- Indexed, validated
CREATE INDEX ON CV (__labels__) UNIQUE;
CREATE INDEX ON CV (name) NOTUNIQUE;
-- Other properties stored schemaless
```

### 3. Fully Typed (Maximum Validation)

```sql
CREATE VERTEX TYPE CV;
CREATE PROPERTY CV.__labels__ LIST OF STRING;
CREATE PROPERTY CV.name STRING (mandatory);
CREATE PROPERTY CV.age INTEGER;
CREATE PROPERTY CV.email STRING;
CREATE INDEX ON CV (__labels__) UNIQUE;
CREATE INDEX ON CV (name) NOTUNIQUE;
CREATE INDEX ON CV (email) UNIQUE;
```

## Migration Path

1. **Create CV and CE types** with `__labels__` property and index
2. **Export Neo4j data** to CSV, JSON, or use neo4j-admin dump
3. **Import into ArcadeDB**: Map labels to `__labels__` array, properties to schemaless properties
4. **Run Cypher queries**: The translator handles CV/CE mapping automatically
5. **Optimize over time**: Add schema definitions for frequently-queried properties
6. **Migrate to native types** (optional): For stable parts of your schema, create native types for better performance

## Naming Rationale

- **CV** = "Cypher Vertex" (2 characters)
- **CE** = "Cypher Edge" (2 characters)

Short names follow ArcadeDB conventions (V for Vertex, E for Edge) and minimize storage overhead. At scale, the difference between "CV" (2 bytes) and "CypherVertex" (13 bytes) saves 11 bytes per vertex -- significant at billions of vertices.
