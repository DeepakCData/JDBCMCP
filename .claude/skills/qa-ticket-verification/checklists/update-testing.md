# UPDATE Testing Checklist

Use when asked to "test updates" on a table with no further detail. Shared rules are in
[README.md](README.md). Connect with `read_only: false`.

**Target rows:** prefer rows created via [insert-testing.md](insert-testing.md) (session-marker
rows). If you must touch pre-existing rows, get the engineer's explicit OK and **capture a
before-image first** — `SELECT *` of every row you'll modify, kept so you can restore it.

**Hard rule: never run an UPDATE without a WHERE clause outside a scratch table you created.**

---

## Tier 0 — Preconditions

1. Metadata: identify the PK, updatable vs **read-only columns** (CData marks computed/system
   columns — e.g. CreatedDate, formula fields; attempting to set one is a *negative* test, not a
   surprise), types and lengths.
2. Baseline: `COUNT(*)`, plus before-image of target rows.
3. Pick a **control row** you will never touch — its values are re-checked at the end to prove
   the WHERE clauses didn't leak.

## Tier 1 — Core (always run)

| # | Test | How | Pass when |
|---|---|---|---|
| 1.1 | Single column by PK | `UPDATE <T> SET colA = <new> WHERE <PK> = <k>` | Update count = 1 |
| 1.2 | Read-back | `SELECT` the row | colA has the new value; **every other column unchanged** vs before-image |
| 1.3 | Multi-column | `SET colA = ..., colB = ...` in one statement | Count = 1; both changed, rest untouched |
| 1.4 | No-match update | `WHERE <PK> = <nonexistent>` | Update count = **0, no exception**. (Flip side: 0 when a match was expected is usually the bug — see SKILL.md pitfalls) |
| 1.5 | Prepared update | 1.1 via `execute_prepared` (`SET colA = ? WHERE <PK> = ?`) | Count = 1; trace shows both params bound in the right order |
| 1.6 | Control row intact | `SELECT` the control row | Identical to its before-image |

## Tier 2 — Predicates and values

- **WHERE on a non-key column** matching multiple session rows → update count equals the
  previously-measured matching count; all matched rows changed, non-matching untouched.
- **Compound WHERE** (`AND` / `OR` with parentheses) → count matches a pre-computed
  `SELECT COUNT(*)` with the same predicate. Run the COUNT first, then the UPDATE — the two
  numbers must agree.
- **SET to NULL** on a nullable column → read back NULL (`wasNull()` for numerics).
- **SET NULL on a NOT NULL column** → proper CData error, row unchanged.
- **SET to the same value** (idempotent update) → report the observed update count: some
  drivers/back-ends return 1 (row matched), some 0 (row unchanged) — either is acceptable,
  but it must not error.
- **SET with expression** `SET num = num + 1` (where the dialect supports it) → read back
  old value + 1. If the driver rejects expressions, capture the error as observed behaviour.

## Tier 3 — Type boundaries & negatives

Same boundary battery as INSERT Tier 3, applied through UPDATE (drivers route these through
different code paths, so passing on INSERT does not imply passing on UPDATE):

- Precision/length overflow → CData-wrapped error (`HY000`, `STMT`-prefixed native text), row
  keeps its old value — **verify the old value survived**, a mangled row after a failed update
  is a serious finding.
- Special characters / unicode via prepared params → exact read-back.
- **Update a read-only/computed column** → clear CData error naming the column; row unchanged.
- **Update the PK itself** (where meaningful) → report observed behaviour: allowed (row
  re-keyed, old key gone, new key present) or properly rejected. Verify whichever happened is
  complete — a half-applied key change is a FAIL.
- Invalid date / wrong type literal → clear error, no change.

## Tier 4 — Wire-level (HTTP/cloud drivers)

- Trace shows **PATCH/PUT/POST-update** as the connector documents, targeting the right record
  id, with **only the SET columns** in the body (full-record PUT where the API requires it is
  fine — but report a full-body PUT on a PATCH-capable API, it clobbers concurrent edits).
- One matched row = one update call; a multi-row UPDATE that fans out to N calls is expected on
  most REST connectors, but N should equal the matched count — more means the driver re-fetched
  or retried.
- Read-back hits the backend (fresh GET), not a driver cache.

## Cleanup (mandatory)

Restore before-images of any pre-existing rows you touched (an UPDATE back to old values, then
read-back), delete session-marker rows if this was a standalone run, verify baseline `COUNT(*)`
and the control row one last time.

## Verdict guidance

- **FAIL**: wrong rows modified (WHERE leak), unmodified columns changed, update count disagreeing
  with rows actually changed, failed update leaving a partially-modified row, silent success on a
  read-only column, misleading error.
- Report idempotent-update count, PK-update behaviour, and expression support as **observed
  behaviours** when not clear-cut pass/fail.
