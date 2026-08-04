# DELETE Testing Checklist

Use when asked to "test deletes" on a table with no further detail. Shared rules are in
[README.md](README.md). Connect with `read_only: false`.

**Hard rules:**
- Delete only rows **you created this session** (marker rows from
  [insert-testing.md](insert-testing.md)) or rows the engineer explicitly designated as
  disposable. Deletes are often unrecoverable — when in doubt, ask before executing, and say
  which keys you intend to delete.
- **Never run `DELETE FROM <T>` without a WHERE clause** except on a scratch table you created.
- If no deletable rows exist and writes are allowed, run the INSERT checklist's Tier 1 first to
  create fixtures — DELETE tests need bodies.

---

## Tier 0 — Preconditions

1. Metadata: PK, and whether the connector documents **soft-delete** semantics (many SaaS
   back-ends flag records `IsDeleted` instead of removing them — Salesforce is the classic case).
2. Baseline `COUNT(*)`; list of fixture keys to delete; a **control row** that must survive.

## Tier 1 — Core (always run)

| # | Test | How | Pass when |
|---|---|---|---|
| 1.1 | Delete by PK | `DELETE FROM <T> WHERE <PK> = <k>` via `execute_update` | Update count = 1 |
| 1.2 | Verify gone | `SELECT ... WHERE <PK> = <k>` | 0 rows |
| 1.3 | Count moved | `COUNT(*)` | Baseline − 1 |
| 1.4 | No-match delete | `WHERE <PK> = <nonexistent>` | Update count = **0, no exception** |
| 1.5 | Re-delete same key | Repeat 1.1 for the already-deleted key | Count = 0, no exception (idempotence); an error here is a finding |
| 1.6 | Prepared delete | Another fixture via `execute_prepared` (`WHERE <PK> = ?`) | Count = 1; trace shows the bound param; row gone |
| 1.7 | Control row intact | `SELECT` control row | Still present, unchanged |

## Tier 2 — Predicates

- **Multi-row delete**: predicate matching several fixture rows (e.g. the session marker).
  First `SELECT COUNT(*)` with the predicate, then DELETE → update count equals that count;
  re-count confirms exactly that many gone and total = baseline − N.
- **Compound WHERE** (`AND`/`OR` with parentheses) on fixtures — same count-first-then-delete
  reconciliation. A DELETE removing more rows than its predicate's COUNT is a critical WHERE-leak
  finding.
- **Delete with non-key predicate only** (no PK in WHERE) — verify only matching rows vanished.

## Tier 3 — Negatives & semantics

- **FK / referential constraint** (native DBs, or API-enforced relations): delete a parent row
  that has children → proper CData error (`HY000`, message naming the constraint), row still
  present. Only run against fixture parent/child rows you created.
- **Read-only / non-deletable table**: if the backing API has no DELETE endpoint, a
  correctly-formed DELETE must return a clear "not supported" CData error — report the suite as
  **N/A (non-deletable table)**, not FAIL, once confirmed by metadata/docs.
- **Soft-delete verification** (when Tier 0 flagged it): after 1.1, the record should be gone
  from default queries, but check whether the connector exposes it via an `IsDeleted`/`Deleted=true`
  filter or a "include deleted" property — report which semantics the driver implements.
- **Permissions**: a delete rejected by the backend (403-style) must surface as a clean CData
  error (`HTTP [...]` prefixed for cloud drivers), not a silent count of 0.

## Tier 4 — Wire-level (HTTP/cloud drivers)

- Trace shows the **DELETE verb** (or the API's documented deletion call) with the right record
  id — one call per row.
- A multi-row SQL DELETE fanning out to N API calls is normal for REST connectors; N must equal
  the matched count. Watch for a pre-fetch SELECT before the deletes (drivers resolve keys
  first) — expected, but N+1 *per row* beyond that is not.
- The verify-gone SELECT (1.2) reaches the backend in the trace — proves server-side removal,
  not a cache eviction.

## Cleanup

Usually self-cleaning (the tests delete the fixtures). Finish by deleting any leftover
session-marker rows, confirm `COUNT(*)` = original pre-INSERT baseline, and confirm the control
row survives. Report any fixture row that refused to die, with its key.

## Verdict guidance

- **FAIL**: WHERE leak (extra rows gone — severity: critical), update count disagreeing with rows
  actually removed, row still present after count=1, silent 0-count on a permission/constraint
  failure, misleading error.
- **N/A**: non-deletable table by design.
- Report idempotent re-delete behaviour and soft-vs-hard delete semantics as **observed
  behaviours**.
