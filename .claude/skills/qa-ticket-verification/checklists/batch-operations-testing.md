# Batch Operations Testing Checklist

Use when asked to "test batch operations", "bulk insert", "executeBatch", or "BatchSize". Shared
rules are in [README.md](README.md). Connect with `read_only: false`.

Batch tests run through **`execute_java`** — `execute_prepared` is single-statement. All rows
carry the session marker for cleanup. Everything from
[insert-testing.md](insert-testing.md) about writability (Tier 0) applies first: a read-only
table makes this whole suite **N/A**.

---

## Tier 1 — PreparedStatement batch (the core case, always run)

```java
PreparedStatement ps = connection.prepareStatement(
    "INSERT INTO <T> (colA, colB) VALUES (?, ?)");
for (int i = 0; i < 10; i++) {
    ps.setInt(1, 9000 + i);
    ps.setString(2, "MCP_QA_batch_" + i);
    ps.addBatch();
}
int[] counts = ps.executeBatch();
__out.append("len=").append(counts.length).append(" counts=");
for (int c : counts) __out.append(c).append(",");
```

Verify **all** of:

| # | Check | Pass when |
|---|---|---|
| 1.1 | Return array length | `counts.length == 10` — exactly one entry per `addBatch` |
| 1.2 | Element values | Each is `1` or `Statement.SUCCESS_NO_INFO` (−2). Report which the driver returns — SUCCESS_NO_INFO is legal but worth stating. `0` entries on rows that were actually inserted = wrong counts, a finding |
| 1.3 | Data landed | `SELECT COUNT(*) WHERE <marker>` = 10; spot-read 2–3 rows for value fidelity (right values in right rows — ordering bugs show up here) |
| 1.4 | Wire shape | See Tier 3 — count the actual backend calls |

## Tier 2 — Batch API semantics

- **`getGeneratedKeys()` after `executeBatch()`** (prepare with `RETURN_GENERATED_KEYS`): drivers
  vary — all keys, last key only, or empty. Report the observed behaviour; empty is not a FAIL
  unless a ticket claims support.
- **`clearBatch()`**: add 5, `clearBatch()`, add 2, execute → counts length 2, exactly 2 rows
  created. Stale batched rows leaking through is a bug.
- **Empty batch**: `executeBatch()` with nothing added → zero-length array, no exception.
- **Reuse after execute**: add + execute a second batch on the same PreparedStatement → works,
  first batch not re-executed (total rows = batch1 + batch2, not 2×batch1 + batch2).
- **Statement batch** (non-prepared): `stmt.addBatch("INSERT ...")` with 2–3 literal statements,
  optionally mixing INSERT and UPDATE → per-statement counts correct, effects verified by
  read-back.
- **Batch of UPDATEs / DELETEs** via PreparedStatement — same count/read-back discipline as the
  insert batch; each param set must hit its own row (verify no cross-row bleed).

## Tier 3 — BatchSize / wire behaviour (CData drivers)

CData drivers expose a **`BatchSize`** connection property that groups batched rows into bulk API
requests where the backend supports it.

1. Run the Tier 1 batch (N=10, or larger, e.g. 50) and **count the data-modifying calls in the
   trace** (mitmproxy JSONL / driver log): with bulk support and `BatchSize >= N` expect ~1 call;
   with `BatchSize = 5` expect ~N/5 calls. **10 rows = 10 individual POSTs on a bulk-capable API
   is the N+1 finding this tier exists to catch.**
2. Reconnect with an explicit different `BatchSize` and re-run — call count in the trace must
   change accordingly. (New session: property changes require reconnect.)
3. Native TCP drivers: no HTTP trace — use `intercepted_calls` and timing instead; a batch that
   executes as N separate round-trips shows N executes and roughly linear `duration_ms`.

## Tier 4 — Failure semantics

Build a batch where **one middle entry must fail** (e.g. row 5 of 10 violates precision/length or
duplicates a PK — pick from metadata, per INSERT Tier 3):

- Expect **`BatchUpdateException`** (or the CData-wrapped equivalent — assert on what actually
  surfaces, per README). Capture `getUpdateCounts()` from the exception.
- Determine and report the driver's model: **fail-fast** (counts shorter than the batch, rows
  after the failure not attempted) vs **continue-on-error** (full-length array with
  `EXECUTE_FAILED` (−3) at the failed slot).
- **Reconcile with the data**: rows the counts claim succeeded must exist; the failed row must
  not; rows after the failure must match the model the counts reported. Counts that disagree
  with the table state are the serious finding.
- Error message quality: the exception should identify the failing row/value at the CData layer
  (`HY000`, `STMT`-prefixed native text) — a bare NPE or an error blaming the wrong row is a bug.

## Tier 5 — Scale probe (optional, when performance is in scope)

- 500–1000 row batch: wall-clock via `execute_java` timing, trace call count, memory sanity.
  Compare per-row time vs the 10-row batch — grossly superlinear growth is a finding.

## Cleanup

Delete all marker rows (a batch DELETE is fine — it's one more test), verify baseline count.

## Verdict guidance

- **FAIL**: counts array wrong length/values vs actual table state, cross-row value bleed,
  clearBatch leak, batch re-execution on reuse, N individual calls despite bulk-capable API and
  large BatchSize, failure model where counts contradict the data.
- Report as **observed behaviour**: SUCCESS_NO_INFO vs exact counts, generated-keys support,
  fail-fast vs continue-on-error.
