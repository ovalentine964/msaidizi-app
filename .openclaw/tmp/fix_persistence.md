# Persistence Fix: Knowledge Graph & Agent Sessions

## Problem
The knowledge graph (`CrossDomainKnowledgeGraph`) and agent sessions (`HermesSessionManager`) were in-memory only — stored in `ConcurrentHashMap` instances that vanish when the OS kills the app process. On 2GB devices, this happens frequently, causing:
- Loss of learned cross-domain insights (sales ↔ inventory correlations)
- Loss of worker session context (conversation history, trace steps)
- Loss of learned skills and worker profiles
- Forced cold-start recovery on every app restart

## Solution: Room as Durable Layer (Not Replacement)

The in-memory `ConcurrentHashMap` remains the **hot cache** for fast reads. Room/SQLite serves as the **durable persistence layer** that survives process death. This is a cache-aside pattern:

```
┌─────────────────┐     ┌──────────────┐
│  ConcurrentHashMap│ ◄── │   Room/SQLite │
│  (hot cache)      │ ──► │  (durability) │
└─────────────────┘     └──────────────┘
```

### Key Design Decisions
1. **Lazy hydration** — Graph is loaded from Room on first access, not at startup
2. **Write-through** — Every mutation writes to both cache and Room (async)
3. **Per-worker lazy loading** — Sessions are restored individually, not bulk-loaded
4. **TTL cleanup** — Old sessions auto-expire (7-day default)
5. **Foreign key cascades** — Deleting a node cascades to its edges; deleting a session cascades to its traces

## Files Created

### Entities
| File | Purpose |
|------|---------|
| `core/database/KnowledgeNodeEntity.kt` | Room entity for graph nodes (facts, patterns, insights) |
| `core/database/KnowledgeEdgeEntity.kt` | Room entity for graph edges (cross-domain relations) |
| `core/database/AgentSessionEntity.kt` | Room entity for Hermes session state |
| `core/database/AgentTraceEntity.kt` | Room entity for reasoning traces (OODA/ReAct steps) |

### DAOs
| File | Purpose |
|------|---------|
| `core/database/KnowledgeDao.kt` | CRUD + graph queries + batch load + prune |
| `core/database/SessionDao.kt` | CRUD + time-based queries + TTL cleanup |

## Files Modified

### `core/database/AppDatabase.kt`
- Added 4 new entities to `@Database` annotation
- Bumped version from 12 → 13
- Added abstract DAO methods: `knowledgeDao()`, `sessionDao()`

### `core/di/AppModule.kt`
- Added migration v12 → v13 (creates all 4 tables with indexes)
- Added `@Provides` for `KnowledgeDao` and `SessionDao`
- Added `@Provides` for `CrossDomainKnowledgeGraph` (now receives `KnowledgeDao`)
- Added `@Provides` for `HermesSessionManager` (now receives `SessionDao`)
- Wired `HermesSessionManager` into `ConversationManager`
- Wired `CrossDomainKnowledgeGraph` into `Orchestrator`

### `agent/knowledge/CrossDomainKnowledgeGraph.kt`
- Added `KnowledgeDao` constructor parameter
- Added `hydrateFromDisk()` — lazy-loads graph from Room on first access
- `addFact()`, `addPattern()`, `addInsight()` now write-through to Room
- `discoverRelations()` persists new edges to Room
- `pruneIfNeeded()` deletes pruned nodes/edges from Room

### `agent/hermes/HermesSessionManager.kt`
- Added optional `SessionDao` constructor parameter
- `getOrCreateSession()` now tries Room restore before creating new
- `recordTraceStep()` persists trace steps to Room
- `completeInteraction()` persists updated session to Room
- Added `cleanupExpiredSessions()` for TTL-based cleanup (7-day default)

## Database Schema (v12 → v13 Migration)

```sql
-- Knowledge nodes (facts, patterns, insights)
CREATE TABLE knowledge_nodes (
    node_id TEXT PRIMARY KEY,
    node_type TEXT NOT NULL,
    domain TEXT NOT NULL,
    key TEXT NOT NULL,
    value_json TEXT NOT NULL,
    confidence REAL NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

-- Knowledge edges (cross-domain relations)
CREATE TABLE knowledge_edges (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    from_node TEXT NOT NULL REFERENCES knowledge_nodes(node_id) ON DELETE CASCADE,
    to_node TEXT NOT NULL REFERENCES knowledge_nodes(node_id) ON DELETE CASCADE,
    relation_type TEXT NOT NULL,
    strength REAL NOT NULL,
    shared_keys_json TEXT NOT NULL DEFAULT '[]',
    created_at INTEGER NOT NULL
);

-- Agent sessions (Hermes protocol)
CREATE TABLE agent_sessions (
    session_id TEXT PRIMARY KEY,
    worker_id TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    last_active INTEGER NOT NULL,
    last_channel TEXT NOT NULL DEFAULT 'app',
    context_window_json TEXT NOT NULL DEFAULT '[]',
    active_trace_id TEXT,
    active_skill_ids_json TEXT NOT NULL DEFAULT '[]',
    last_skill_query TEXT
);

-- Agent traces (reasoning steps)
CREATE TABLE agent_traces (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT NOT NULL REFERENCES agent_sessions(session_id) ON DELETE CASCADE,
    trace_id TEXT NOT NULL,
    step_index INTEGER NOT NULL,
    action TEXT NOT NULL,
    tool_used TEXT,
    success INTEGER NOT NULL DEFAULT 1,
    error TEXT,
    duration_ms INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
);
```

## Indexes
- `knowledge_nodes`: domain, node_type, (domain+node_type), updated_at, confidence
- `knowledge_edges`: from_node, to_node, (from_node+to_node) UNIQUE, relation_type
- `agent_sessions`: worker_id, last_active, (worker_id+last_active)
- `agent_traces`: session_id, trace_id, created_at, (session_id+trace_id)

## Impact on 2GB Devices
- **No startup penalty** — Graph hydrates lazily in background coroutine
- **Minimal memory** — Only requested worker session is loaded (not all sessions)
- **Async writes** — Room writes don't block the UI thread
- **WAL mode** — Existing database config handles concurrent reads during writes
- **TTL cleanup** — Prevents unbounded growth of old sessions
