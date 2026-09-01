# pi session/harness parity audit — Pathfinder session layer vs upstream

Audit, 2026-09 — now a **living ledger**: every item below carries a Status
line (`landed in <commit>`, `deliberately excluded`, or `remaining`) and is
refreshed as work lands. Compares:

- **Pathfinder**: `app/src/main/kotlin/works/resolve/pathfinder/data/sessions/`
  (`SessionEntry.kt`, `Conversation.kt`, `SessionCodec.kt`, `Session.kt`,
  `SessionStore.kt`, `SessionRepository.kt`) and
  `app/src/main/kotlin/works/resolve/pathfinder/agent/` (`Agent`/`AgentSession`
  facades, `agent/compaction/`).
- **pi** (`~/Projects/pi`, behavioral source of truth):
  `packages/agent/src/harness/session/` (`types.ts`, `state.ts`, `session.ts`,
  `context.ts`, `jsonl/`), `packages/agent/src/harness/reducer.ts`,
  `packages/agent/src/harness/compaction/` (incl. `branch-summarization.ts`),
  `packages/session-backends/`.

**Interplay with the parallel entry-type effort.** A parallel effort is already
porting the `model_change`, `thinking_level_change`, and `active_tools_change`
entry kinds and the reducer's configuration fold. This audit therefore focuses
on the **structural** model underneath those kinds: sequence numbers, the
mutation-log storage format, lanes, records, lineage, and recovery. Every item
below that touches the codec/state layer (P0-1, P0-2, P1-2) is a **prerequisite
or co-requisite** for that effort — the new entry kinds should land *in* the
JSONL-v4 mutation format, not in Pathfinder's current whole-file format 2.

---

## Already aligned (checked, found faithful)

- **Entry tree primitives** — `SessionEntry` (id, parentId, timestamp) and
  `Conversation.activeEntries()` (root→leaf walk with cycle guard) match pi's
  entry tree walk (`session/state.ts` `walkToRoot`, which throws on cycles;
  Pathfinder's `seen` set in `activeEntries` is the same guard).
- **Compaction entry shape** — `CompactionEntry` carries `summary`,
  `retainedTail`, `tokensBefore`, `details`, `usage` per pi's
  `session/types.ts` `CompactionEntry`; the documented divergences (typed
  `details`, missing `seq`) are correct and will be resolved by P0-1.
- **Compaction core** — `agent/compaction/Compaction.kt` is a close port of
  `compaction/compaction.ts` (cut points, usage combination, retry, prompts),
  with per-symbol provenance KDoc and documented divergences (signal →
  coroutine cancellation, `details` typing).
- **Context projection** — `SessionContext.kt` faithfully ports
  `defaultContextEntryTransform` (latest compaction + tail) and the reduced
  `sessionEntryToContextMessages`; `getLatestCompactionEntry` matches
  upstream's boundary guard.
- **Iterative `tree()`** — mirrors pi's deep-tree concern (upstream documents
  stack overflow on recursive `getTree()`); orphan promotion to roots matches.
- **UUIDv7 ids** — `Conversation`/`SessionStore` default to `uuidv7`, pi's
  `idGenerator` default (`session/session.ts:22`).
- **Atomic persistence discipline** — `SessionStore` writes
  temp-file + atomic-replace with a documented non-atomic fallback, bounded
  reads, id/filename cross-check, and defensive copies; the same
  all-or-nothing intent as pi's `publishFileAtomically`
  (`jsonl/storage.ts:35`), within Pathfinder's snapshot format.
- **AgentSession layering** — retry/post-run-continuation ownership mirrors
  pi's `agent-session.ts` split, and deliberate exclusions (steer/follow-up
  queues, manual compaction, branch summarization, extension events) are
  documented at the facade boundary.

---

## P0 — structural foundations (block or distort everything above them)

### P0-1. Shared storage-assigned sequence number (`seq`)

**Status:** landed — storage-assigned consecutive `seq` + replay validation (42de290).

- **pi**: `session/types.ts:17` (`EntryBase.seq`, "shared sequence;
  read-side, storage-assigned"); enforced consecutive in
  `session/state.ts:118` `applyMutation` (`has non-consecutive seq`).
  `seq` orders entries, records, and log items, drives cursors
  (`EntryQuery.cursor.afterSeq`, `RecordQuery.afterSeq`), and is part of every
  persisted line.
- **Pathfinder**: `SessionEntry.kt` has no `seq` (documented divergence on
  `CompactionEntry` only, but the omission is structural). Ordering relies on
  timestamps, which are not unique or strictly monotonic across branches.
- **Faithful port**: add `seq: Long` (storage-assigned, 1-based, consecutive)
  to the mutation/entry model; validation rejects non-consecutive or
  non-positive seq on replay.
- **Size**: S — the field itself is small, but it lands together with P0-2
  (the writer that mints it). ~1–2 days including tests.

### P0-2. JSONL v4 mutation-log storage format (header + entry/record/lane/fact mutations, append-only)

**Status:** landed — JsonlCodec header/mutation lines (d4fe543), append-only JsonlSessionStorage/SessionStore with torn-tail repair (6b497c5).

- **pi**: `jsonl/codec.ts` — `decodeHeader` (version 4, `id`, `createdAt`,
  `cwd`, `parentSessionId`, `legacyParentSessionPath` — mutually exclusive —
  and free-form `metadata`); `decodeMutation` with four mutation kinds
  (`entry` [optionally lane-addressed], `record`, `lane`, `fact`);
  `jsonl/storage.ts` — append-only writes (`appendFile` per mutation,
  serialized through a `tail` promise), replay on load with `SessionState`
  validation, torn-tail repair (staged rewrite + newline repair,
  `storage.ts:80–106`), and `metadataFromHeader` for listing.
- **Pathfinder**: `SessionCodec.kt` encodes a whole-file JSON snapshot
  ("format 2": title, entries, leafId) and `SessionStore.save` rewrites the
  entire file on every save (bounded at 16 MB). No mutation kinds, no header
  lineage, no replay validation, no torn-tail recovery. Per repo policy the
  old format is rejected, not migrated — the port would bump the version and
  drop format 2.
- **Faithful port**: a JSONL codec writing the v4 header line
  (`kind:"header", version:4, id, createdAt, cwd?…`) plus one mutation line
  per write; a `SessionState` equivalent that replays mutations and validates
  (consecutive seq, lane chaining, parent existence, duplicate ids); on-load
  torn-tail repair. `cwd` has no Android meaning — document as a deliberate
  omission or carry an app-specific value in `metadata`.
- **Size**: M/L — ~1 week (codec + replay-state + storage + store rewrite +
  tests). The single biggest catch-up item; everything else below rides on it.

### P0-3. Lane records: operation lifecycle durability

**Status:** landed — operation lifecycle trio + usage records (3407524).

- **pi**: `session/types.ts` `RecordBase` + `LaneRecord` union — at minimum
  `operation_started` (with `sourceLeafId` and `intent`:
  run/compaction/navigation), `abort_requested` (runId), `operation_finished`
  (runId, outcome, error); full union adds `step_attempt`, `tool_started`,
  `queue_enqueued`, `queue_cancelled`, `write_deferred`, `usage`. Open
  operations are tracked per lane (`state.ts` `openOperationsByLane`) and
  recovered via `findOpenOperations` (`limit: 2` recovery contract,
  `types.ts:237`).
- **Pathfinder**: nothing. `AgentSession` keeps run/abort state purely in
  memory; process death loses the notion of an in-flight operation.
- **Faithful port**: record kinds appended to the mutation log; at minimum
  the operation lifecycle trio so an interrupted run is detectable on next
  load. The queue/tool/usage/step_attempt kinds can follow incrementally
  (steer/follow-up queues remain a documented exclusion until ported).
- **Size**: M for the lifecycle trio (~3–4 days); the full union is another
  M on top and is only meaningful with the reducer (P1-5).

---

## P1 — structural features with clear user-visible payoff

### P1-1. Lanes

**Status:** landed — lanes map, LaneView projection, createLane/moveLane (6689c67). UI stays single-lane by product decision.

- **pi**: multiple named lanes each holding a leaf pointer
  (`types.ts` `LanePointer`, `state.ts` lanes map seeded with `main`);
  `Session.view(lane)` (`session/session.ts:66`) projects a lane-scoped
  `SessionTree`; `createLane`/`moveLane` mutate pointers; lane mutations are
  persisted (`kind:"lane"`).
- **Pathfinder**: one implicit leaf (`Conversation.leafId` /
  `Session.leafId`); `branch()` is `moveLane("main")`-equivalent, and
  `resetLeaf()` has no upstream counterpart in the lane model.
- **Faithful port**: a lanes map with the `main` default, entry mutations
  optionally lane-addressed, and view projection. Android's single-chat-UI
  may never expose non-main lanes, but the storage model should carry them so
  pi-produced semantics replay correctly.
- **Size**: M (~3 days), rides on P0-2.

### P1-2. Session lineage and fork

**Status:** landed — parentSessionId lineage + fork with branch/tree scopes (6689c67).

- **pi**: `parentSessionId` in the v4 header (with `legacyParentSessionPath`
  compat read); `SessionRepo.create/open/list/delete/fork`
  (`types.ts:289`, `jsonl/repo.ts:142`); `fork` with `{scope:"branch",
  entryId, position:"before"|"at"}` or `{scope:"tree"}` producing mutation
  batches via `state.ts` `createForkMutations` (entries, lanes, name/label
  facts re-seq'd from 1); `fork` defaults `parentSessionId` to the source id.
- **Pathfinder**: no parent linkage, no fork. `SessionStore` has
  create/summaries/load/save/delete only.
- **Faithful port**: header-level `parentSessionId`; a `fork` that produces
  the same mutation batch semantics (branch scope requires a message entry
  target, `invalid_fork_target` otherwise). This is the natural backing for
  any future "branch chat" UI.
- **Size**: M (~3–4 days).

### P1-3. Global facts: session name and entry labels

**Status:** mostly landed — name-as-fact replaced `title` in the v4 port; label facts (decode/apply, `storage.setLabel`, fork fact copy) landed with the codec/state ports. **Remaining (deliberate):** label producers — upstream writers are coding-agent extensions (tree-view labeling) and pathfinder has no extension runner; the UI boundary is documented in `AgentSession.navigationIntent`'s KDoc.

- **pi**: `fact` mutations (`fact:"name"` / `fact:"label"` with `targetId`),
  latest-wins, persisted in the log; labels validated against existing
  entries (`state.ts` fact case). Session "name" is distinct from any entry.
- **Pathfinder**: `Session.title` — a snapshot field mutated out-of-band, no
  labels.
- **Faithful port**: name-as-fact replaces `title` in the new format; entry
  labels back the tree-view labeling UI with durability.
- **Size**: S (~1 day) once P0-2 exists.

### P1-4. Branch summarization

**Status:** landed — entry kind + collect/prepare/generate core (a80c222), navigation `summarize` trigger (fe14700).

- **pi**: `compaction/branch-summarization.ts` — `collectEntriesForBranchSummary`
  (common-ancestor walk), `prepareBranchEntries` (token budget, file-ops
  merge, compaction/branch-summary entry inclusion rules),
  `generateBranchSummary` (fixed prompt format, preamble, file-ops appendix);
  `branch_summary` entry kind (`types.ts:48`) with `fromId`; projected into
  context by `context.ts` (`createBranchSummaryMessage`); triggered by the
  navigation operation intent (`summarize: boolean`).
- **Pathfinder**: absent — and **currently documented as a deliberate
  exclusion** in `AgentSession`'s KDoc. If it stays excluded, the divergence
  note is the required artifact; if ported (recommended once navigation exists),
  it needs: the entry kind, the three functions above, and the navigation
  hook. The summarization plumbing it needs (`completeSimpleWithRetries`,
  `serializeConversation`, `estimateTokens`) is already ported in
  `compaction/`.
- **Size**: M (~4 days) including the navigation trigger.

### P1-5. Durable recovery: the reducer

**Status:** landed — validateRecordLog/reduceLaneState/classifyLaneRecovery with all 12 corruption reasons (fe14700); the restore path now runs the full reduction at session load (agent/session-p2-sweep).

- **pi**: `harness/reducer.ts` — `reduceLaneState` folds a lane's
  `RecordLogSlice` (open operations + records + referenced entries) into
  lane state, classifying corruption via `RecordLogCorruptionReason`
  (multiple open operations, record after finish, non-consecutive attempt,
  tool-call mismatch, ...). This is what makes the record log a recovery log
  rather than telemetry. (The **configuration fold** portion is the parallel
  effort's; interplay: the fold consumes configuration entries whose seq
  ordering comes from P0-1.)
- **Pathfinder**: nothing; no crash-recovery contract at all. On reload, an
  interrupted run looks identical to a finished one.
- **Faithful port**: after P0-3, port `reduceLaneState` for the operation
  kinds that exist (run/compaction at minimum) and reject the corresponding
  corruption reasons on restore.
- **Size**: M/L (~1 week), staged with the record kinds it covers.

### P1-6. `terminate` flag and deferred-assistant filtering in context

**Status:** landed — terminate flag + deferred-assistant filtering (a80c222).

- **pi**: `MessageEntry.terminate?: true` (`types.ts:27`) marks
  terminal-of-session messages; `context.ts` `sessionEntryToContextMessages`
  drops assistant messages with `stopReason === "deferred"`.
- **Pathfinder**: neither exists; `SessionContext.kt` emits every message
  entry unconditionally.
- **Faithful port**: optional codec field + one filter in the context
  projection. Needed before any deferred/progressive-response port.
- **Size**: S (~half a day).

---

## P2 — API surface and hygiene (port opportunistically)

### P2-1. Query API (EntryQuery / RecordQuery / BranchBounds / cursors)

**Status:** landed — EntryQuery/RecordQuery/BranchBounds with cursors and orderings; findEntries/findEntriesOnBranch/findRecords/findOpenOperations (6689c67).

- **pi**: `state.ts` `findEntries` / `findEntriesOnBranch` / `findRecords`
  with type filters, `newestFirst`/`oldestFirst`, limits, seq cursors, and
  branch bounds (`stopAtId`/`stopAtType`); `findOpenOperations`.
- **Pathfinder**: `Conversation.activeEntries()` + `firstOrNull` scans;
  no cursoring, so the whole file loads for any query.
- **Port**: query functions over the replayed state; matters most once
  sessions grow and the UI wants paging. **Size**: M (~3 days).

### P2-2. `custom` entries and projectors

**Status:** landed for the codec — `CustomEntry` decodes/encodes and `appendCustomEntry` exists (v4 codec port, d4fe543/6b497c5); `CustomEntryContextMessageProjector` deliberately excluded with the extension runner.

- **pi**: `CustomEntry` (`customType`, `data`), `appendCustomEntry`,
  `CustomEntryContextMessageProjector` in `SessionContextBuildOptions`
  (`context.ts:17`). Extension ecosystem's persistence hook.
- **Pathfinder**: no extension runner — currently a justified exclusion, but
  the entry kind belongs in the codec's entry-type set so third-party lines
  decode instead of being rejected. **Size**: S (~1 day) for codec support
  alone.

### P2-3. `usage` records and `SessionStats`

**Status:** landed — `usage` records fold into `SessionStats`; `stats()` reads the fold (3407524). Summaries still do a bounded full read (documented divergence in `SessionStore`).

- **pi**: `usage` lane records accumulate `SessionStats`
  (messageCount, cached/uncached/total tokens, costTotal) incrementally in
  `state.ts`; `getStats()` is a storage read, no message replay.
- **Pathfinder**: `SessionSummary.messageCount` recomputed by decoding and
  walking every session file. **Size**: S (~1 day) once records exist.

### P2-4. Error taxonomy and payload validation

**Status:** landed — `SessionError`/`SessionErrorCode` replaces `SessionDataException` at every storage-boundary throw site with upstream message text; `assertJsonSerializable` rejects non-JSON-safe payloads before write (agent/session-p2-sweep).

- **pi**: `SessionError` with `SessionErrorCode`
  (`not_found | already_exists | invalid_entry | invalid_payload |
  invalid_lane | invalid_query | invalid_fork_target | storage`);
  `assertJsonSerializable` (`session/session.ts:28`) rejects non-JSON-safe
  payloads before write; `JsonlDecodeError` distinguishes `syntax`/`schema`.
- **Pathfinder**: one `SessionDataException` for everything.
- **Port**: typed error codes at the storage boundary so callers can react
  (notably `invalid_fork_target`, `invalid_lane`). **Size**: S (~1 day).

### P2-5. Incremental log reads (`getLog`)

**Status:** landed — `getLog(afterSeq, limit)` over `LogItem`s: state fold, storage + store passthrough (agent/session-p2-sweep).

- **pi**: `getLog({afterSeq, limit})` returns `LogItem`s since a seq —
  incremental tail reads for observers.
- **Pathfinder**: full snapshot load. Only relevant after P0-2; useful if a
  sync/observer feature ever lands. **Size**: S (~half a day).

### P2-6. SQLite backend

**Status:** deliberately excluded — no Node/SQLite backend on Android; JSONL on local storage is the faithful equivalent. Revisit only if search-over-sessions becomes a feature.

- **pi**: `packages/session-backends/sqlite-node` (repository, migrations,
  materialized views, FTS search) as an alternative `SessionStorage`.
- **Pathfinder**: not applicable as-is (Node backend); JSONL on Android local
  storage is the faithful equivalent. Deliberate exclusion — record here, no
  action unless search-over-sessions becomes a feature (Android would want
  Room/FTS, a separate decision).

### P2-7. Stale provenance references

**Status:** done — citations refreshed (agent/session-p2-sweep). `Conversation`/`AgentSession` citations to coding-agent `session-manager.ts` were verified against current upstream (the file still exists there) and stand.

- Several Pathfinder KDocs cite pi's *old* locations
  (`Conversation.kt` → "pi's SessionManager / session-manager.ts",
  `Conversation.appendCompaction` → session-manager.ts:1098,
  `SessionStore` → "agent jsonl repo" id default). Upstream has moved this
  behavior into `packages/agent/src/harness/session/`. Refresh the citations
  when the corresponding code is ported. **Size**: S (~half a day).

---

## Sequencing (historical; superseded by the ledger above)

1. **P0-1 + P0-2 together** (seq + JSONL v4 codec/state/storage) — the
   parallel entry-type effort should land on top of this, not format 2.
2. **P0-3 operation records** (lifecycle trio) + **P1-3 facts** + **P1-1
   lanes** — all small-to-medium riders on the new format.
3. **P1-2 lineage/fork** (unlocks branch UI), **P1-6** (cheap, do anytime).
4. **P1-5 reducer** once enough record kinds exist; **P1-4 branch
   summarization** once navigation exists.
5. P2 items opportunistically.

Counts: **P0: 3 · P1: 6 · P2: 7**. Top P0s: shared `seq` (P0-1), the JSONL v4
mutation-log format (P0-2), and operation-lifecycle lane records (P0-3).
