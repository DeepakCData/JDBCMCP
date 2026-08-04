# INSERT Testing Checklist

Use when asked to "test inserts/creates/writes" on a table with no further detail. This checklist
is a **baseline** — always execute any specific instructions the engineer gave first, then use
these tiers to fill gaps. If the engineer specifies insert scenarios not listed here, run them.
This checklist is also the entry point of a full "CUD test" run — the rows it creates feed
[update-testing.md](update-testing.md) and [delete-testing.md](delete-testing.md). Shared rules
are in [README.md](README.md).

**Connect with `read_only: false`** (state this before connecting; confirm with the engineer on
shared data). All inserted rows carry a session marker in some text column —
`'MCP_QA_<ticket-or-date>'` — so they are identifiable and cleanable.

---

## Tier 0 — Is the table writable at all?

Before designing inserts:

1. `get_metadata` (`cdata` style): per-column `IsAutoIncrement`, `IsNullable`, `IsKey`,
   `DataTypeName`, `Length`, and any **read-only column** flags. Identify: required columns,
   server-generated columns (exclude from INSERT), max lengths, numeric precision/scale.
2. **Some CData tables are read-only by design** (backing API has GET but no POST — e.g.
   NetSuite's `entityaddress` composite view). If the first correctly-formed INSERT returns a
   "table/operation not supported" style CData error, verify it's by design (metadata, docs,
   sibling tables) and report the INSERT suite as **N/A (read-only table)** — not FAIL.
3. Record `SELECT COUNT(*)` baseline.

## Tier 1 — Core round trip (always run)

| # | Test | How | Pass when |
|---|---|---|---|
| 1.1 | Single insert, explicit column list | `INSERT INTO <T> (colA, colB, ...) VALUES (...)` via `execute_update` | Update count = 1 |
| 1.2 | **Read-back fidelity** | `SELECT` the new row by key/marker | Every inserted value comes back **exactly** — string equality is not enough for decimals/dates: use typed accessors via `execute_java` for those |
| 1.3 | Count moved | `COUNT(*)` | Baseline + 1 |
| 1.4 | Prepared insert | Same shape via `execute_prepared`, all values as `?` params | Count = 1; `intercepted_calls` shows `prepareStatement` + every `setXxx` with the right value; read-back passes |
| 1.5 | Auto-increment / server key | Insert omitting the identity/PK column (if `IsAutoIncrement` or server-generated) | Row created; key populated; **`getGeneratedKeys()`** via `execute_java` returns it (if it returns empty, that's a finding — some drivers don't support it; report observed behaviour) |

`execute_java` skeleton for 1.5:

```java
PreparedStatement ps = connection.prepareStatement(
    "INSERT INTO <T> (colB) VALUES (?)", Statement.RETURN_GENERATED_KEYS);
ps.setString(1, "MCP_QA_...");
__out.append("updateCount=").append(ps.executeUpdate());
ResultSet keys = ps.getGeneratedKeys();
__out.append(" keys=");
while (keys.next()) __out.append(keys.getObject(1)).append(",");
```

## Tier 2 — Column-list and NULL/default semantics

- **Positional insert (no column list)**: `INSERT INTO <T> VALUES (...)` with values for every
  column in metadata order. This is a known CData bug class (DRIVERS-58988: driver mis-mapped
  positional values to columns) — read back and verify **each value landed in the right column**.
- **NULL into a nullable column** — read back `IS NULL` (use `wasNull()` for numerics).
- **Omit a column with a default** — read back the default value, not NULL (unless default is NULL).
- **Empty string** into a text column — read back and report whether it stayed `''` or became
  NULL (Oracle famously converts; cloud APIs vary). Observed behaviour, not assumption.
- **NOT NULL violation**: omit a required non-default column → proper CData error
  (`HY000`, message naming the column/constraint), no partial row created (verify count unchanged).

## Tier 3 — Type boundaries & special values

Drive these from metadata (`Length`, precision/scale). Each negative case must leave the count
unchanged.

- **Numeric precision overflow**: value with more integer digits than the column allows →
  CData-wrapped error (e.g. `SQL Error [HY000]: STMT ORA-01438: value larger than specified
  precision...`). Assert on the CData form, never the bare native code.
- **Scale rounding**: more decimal places than scale — does it round, truncate, or error? Read
  back and report which.
- **String overflow**: `Length + 1` characters → proper error (or documented truncation — read
  back to see which; silent truncation without a driver property enabling it is a finding).
- **Boundary-fit values**: max-length string, max-precision number — must succeed and round-trip.
- **Date/time**: valid edge (e.g. `1900-01-01`, a leap day), invalid date literal → clear error.
- **Special characters**: embedded quote (`O''Brien`), unicode (CJK, emoji), newline, `%`/`_` —
  insert via **prepared** params, read back exact equality. Concatenated-SQL quote handling can be
  tested once separately.
- **Duplicate primary key**: re-insert an existing key → CData constraint error, count unchanged.

## Tier 4 — Wire-level (HTTP/cloud drivers)

- Trace shows **POST** (or the API's documented create verb) to the right endpoint, one call per
  insert (no hidden pre-fetch storm).
- The request body contains the values you sent — spot-check one insert in the mitmproxy
  JSONL/driver log.
- Read-back SELECT actually hits the backend (fresh GET in trace), proving the record exists
  server-side and not just in a driver cache.

## Cleanup (mandatory)

Delete every row carrying the session marker; verify `COUNT(*)` is back to baseline. If a row
can't be deleted (permissions, API), report its key explicitly.

## Verdict guidance

- **FAIL**: wrong column mapping, value corruption on read-back, silent success on an invalid
  insert, misleading error (wrong column named, NPE, native SQLState leaking through unwrapped),
  update count ≠ rows actually created.
- **N/A**: table read-only by design (Tier 0) — say so, don't fail it.
- Report `getGeneratedKeys` support, empty-string behaviour, and scale rounding as **observed
  behaviours** even when they aren't pass/fail.
