# SELECT Testing Checklist

Use when asked to "run select tests", "test reads/queries/filters" on a table with no further
detail. Shared rules (session setup, metadata-first, anchor rows, three-layer verification,
CData-layer errors, reporting) are in [README.md](README.md) — apply them throughout.

Connect with `read_only: true`. Prefer `assert_query` (with `criterion`) over bare
`execute_query` so every check lands in the report.

`<T>` = target table, `<PK>` = its key column, anchor rows = 1–3 known rows fetched up front.

---

## Tier 1 — Core (always run, ~10 checks)

| # | Test | SQL template | Pass when |
|---|---|---|---|
| 1.1 | Bare select | `SELECT * FROM <T> LIMIT 5` | Rows return, no exception, all metadata columns present in result |
| 1.2 | Count | `SELECT COUNT(*) FROM <T>` | Scalar returns; **record it — it is the baseline for everything below** |
| 1.3 | Projection | `SELECT <PK>, <col2>, <col3> FROM <T> LIMIT 5` | Exactly the named columns, in order, values match 1.1's rows |
| 1.4 | Equality on PK | `SELECT * FROM <T> WHERE <PK> = <anchor>` | Exactly 1 row, values match the anchor row |
| 1.5 | Equality on non-key | `WHERE <textcol> = '<anchor value>'` | ≥1 row, every returned row satisfies the predicate |
| 1.6 | Negative filter | `WHERE <PK> = <value that does not exist>` | 0 rows, **no exception** (empty ≠ error) |
| 1.7 | IS NULL / IS NOT NULL | on a nullable column (per metadata) | Row counts of NULL + NOT NULL = 1.2's total |
| 1.8 | ORDER BY | `ORDER BY <col> ASC` then `DESC` | First row of ASC = last of DESC; values actually sorted (check, don't assume) |
| 1.9 | LIMIT semantics | `LIMIT 3` (or TOP/ROWNUM per dialect) | Exactly 3 rows, `truncated` flag understood |
| 1.10 | Prepared parity | Re-run 1.4 via `execute_prepared` with `?` | Same row as 1.4; `intercepted_calls` shows `prepareStatement` + the bound param |

**Row-count cross-check:** whenever a filtered count can be derived (e.g. 1.7), reconcile against
1.2. Mismatch = missing/duplicated rows.

## Tier 2 — Predicates & operators (run unless told to keep it minimal)

Pick columns by type from metadata; use anchor-row values so expectations are known.

- **Comparison set** on a numeric or date column: `>`, `>=`, `<`, `<=`, `<>`, `BETWEEN`.
  Verify complementary predicates partition the table: `count(> x) + count(<= x) = total`.
- **IN / NOT IN** with 2–3 known values (mix one nonexistent value into IN — count must not change).
- **LIKE** three shapes: `'abc%'` (prefix), `'%abc'` (suffix), `'%abc%'` (contains). For cloud
  drivers, check the trace: prefix LIKE often pushes down (`startswith`), contains may fall back
  to client-side evaluation — correct data + full-table fetch in the trace is worth reporting.
- **AND / OR grouping**: `WHERE (a = x AND b = y) OR c = z` — verify against manually derived
  expected rows from the anchor set.
- **Case sensitivity probe**: same string filter in wrong case; note (don't judge) whether the
  driver/back-end is case-sensitive — report the observed behaviour.
- **String with special characters** in a literal (quote `'` doubled, unicode, `%` when not a
  wildcard) — no exception, correct row.

## Tier 3 — Aggregates, grouping, distinct

- `SUM/AVG/MIN/MAX` on a numeric column; cross-check `MIN <= AVG <= MAX` and, on small tables,
  recompute SUM from fetched rows.
- `GROUP BY <col>` with `COUNT(*)`: sum of group counts = 1.2's total. Add `HAVING COUNT(*) > 1`.
- `SELECT COUNT(DISTINCT <col>)` vs `COUNT(<col>)` — distinct ≤ non-null count.
- **NULL semantics**: aggregates ignore NULLs — `COUNT(<nullable col>)` < `COUNT(*)` when NULLs
  exist (known from 1.7).
- `ORDER BY` + `LIMIT` + `OFFSET` pagination: page 1 and page 2 must not overlap and must
  concatenate to the unpaginated top-N. **A result of exactly 100/200/500/1000 rows is a page-size
  smell** — bump `max_rows` and check the trace for pagination calls before trusting the count.

## Tier 4 — Types & JDBC API level (via `execute_java`)

- **Typed accessors** on one row: `getBigDecimal` for decimals, `getObject().getClass()` for
  each column vs `ResultSetMetaData.getColumnType/getColumnTypeName`. String display equality is
  not type verification.
- **ResultSetMetaData on empty result**: `SELECT * FROM <T> WHERE 1=0` — full column metadata
  must still come back (precision, scale, nullability).
- **Date/time round-trip**: `getDate`/`getTimestamp` on a known value; watch for timezone shifts.
- **wasNull()** after reading a NULL numeric — must be true, value 0 is the getter's artifact.

## Tier 5 — Structural edges (when relevant)

- **Quoted / special identifiers**: if table or column names need quoting (spaces, dots, mixed
  case, brackets — seen with MySQL tables like ``my_[s]p,e.(c)`i/a{}\l_repro``), test with the
  dialect's proper quoting; getting this wrong is a classic driver bug.
- **JOIN** to a related table (if an obvious FK exists): row count ≤ cartesian, values line up
  with two single-table queries.
- **Subquery**: `WHERE <PK> IN (SELECT ...)` — same rows as the equivalent JOIN.
- **System views** (CData): `sys_tablecolumns WHERE TableName = '<T>'` agrees with `get_metadata`.

---

## What to check on every response

- `error` is null; if not, capture verbatim (CData-layer message, per README).
- `row_count` vs expectation **and** vs the `truncated` flag.
- `intercepted_calls[*].sql` — did the driver rewrite the query? Is the rewrite semantically equal?
- HTTP drivers: verb is GET, the translated filter (`$filter`, SOQL WHERE, etc.) matches the SQL
  WHERE, and one logical query ≠ many HTTP calls (N+1).
- `duration_ms` — flag anything wildly out of line with sibling queries.

## Verdict guidance

- **PASS**: all executed tiers pass with reconciled counts.
- **FAIL**: any wrong value, wrong/0-when-expected row count, exception on valid SQL, wrong JDBC
  type, or non-equivalent SQL rewrite.
- **NEEDS-INVESTIGATION**: correct rows but suspicious trace (client-side filtering, page-boundary
  counts, heavy N+1) — report with evidence.
- Skipped tiers are reported as skipped, never silently omitted.
