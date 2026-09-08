---
name: driver-conformance
description: Full SQL-surface conformance testing of a CData JDBC driver — what the driver claims, what the backend API supports, and what the driver actually sends. Use when asked to "run a full driver test", "conformance test this driver", "check pushdown", "test the whole SQL surface", "verify what the driver sends to the API", or to sweep operators/functions (LIKE, IN, GROUP BY, HAVING, aggregates, string/date functions) across a driver's tables. Distinct from qa-ticket-verification, which verifies one ticket; this verifies the driver against its own declared contract and the vendor's API. Works for RSD-backed tables and for dynamic drivers with no RSD at all.
---

# Driver Conformance Testing

A driver bug is almost never "the query errored". It is one of these:

- the driver **claims** a filter pushes down and it doesn't (silent full scan)
- the driver **pushes** something the API doesn't support (wrong rows, or a 400 in production)
- the driver **evaluates locally** and returns a *wrong* answer because the scan was capped
- the driver **declares a type** that doesn't match what it returns, or what the API documents

All four are invisible from the result rows alone. You find them by comparing three sources of
truth, and none of them is the result set.

---

## The three contracts

| Source | What it tells you | Where it lives |
|---|---|---|
| **`sys_sqlinfo`** | The driver's declared SQL surface — operators, functions, GROUP BY, aggregates | `SELECT * FROM sys_sqlinfo` (every CData driver, always) |
| **The RSD** | Per column: which operators push down, ORDER BY support, the API-side field name, required/insertable, declared type | `<install>/db/<Schema>/` as `<Table>.rsd`, `<Table>Internal.rsd`, `<Table>CloudInternal.rsd` or `<Table>ServerInternal.rsd` — **when one exists** |
| **The vendor API docs** | What the backend actually supports, and its limits | Vendor documentation. Ask for the link if you do not have it — do not guess. |

And the fourth thing, which is not a contract but the evidence: **the capture**. Every
`execute_*` response returns `_meta.capture_from` / `capture_to` — read that byte range of
`mitm_log_path` to see exactly what the driver sent for that one call.

### Tier the table, not the driver

RSDs are per table, and a driver can have both kinds. ServiceNow ships ~6 RSDs for hundreds of
tables; MySQL, Oracle, SAP HANA and Shopify ship none at all. **Check per table:**

```
look for ANY of these under <install>/db/**/ :
    <Table>.rsd                  e.g. Sprints.rsd, Boards.rsd
    <Table>Internal.rsd          e.g. IssuesInternal.rsd
    <Table>CloudInternal.rsd     e.g. ProjectsCloudInternal.rsd, UsersCloudInternal.rsd
    <Table>ServerInternal.rsd    e.g. WorklogsServerInternal.rsd

   found     -> Tier A: declared per-column contract available
   none      -> Tier B: sys_sqlinfo + metadata only; derive the rest by probing
```

**Match all four spellings, not just `<Table>.rsd`.** CData routinely backs an exposed table with an
`…Internal` RSD, and ships separate `…CloudInternal` / `…ServerInternal` variants selected by
deployment type. Checking only the plain name mis-tiers those tables as dynamic and throws away the
declared contract you actually have. On the Jira driver, matching only `<Table>.rsd` finds 2 of the
7 core tables; matching all four finds all 7. When both Cloud and Server variants exist, the one
that applies is decided by the target deployment — a `*.atlassian.net` URL is Cloud.

`sys_sqlinfo` is available in **both** tiers. It is the one contract you always have.

> **Never skip the probe just because an RSD exists.** An RSD saying a column is not filterable is
> a *claim*, not a justification — if the API supports filtering it, the driver is leaving a full
> scan on the table and the RSD is simply out of date. Tier A tells you whether the driver obeys
> itself; only the probe plus the API docs tell you whether it should.

---

## Phase 1 — Read the declared surface (cheap, once per driver)

```sql
SELECT * FROM sys_sqlinfo
```

Roughly 40 rows. The ones that matter:

| Key | Why it matters |
|---|---|
| `SUPPORTED_OPERATORS` | The operators the driver *guarantees* it pushes. Absence is a hypothesis, not a fact — see the floor note below |
| `GROUP_BY`, `COUNT`, `AGGREGATE_FUNCTIONS` | `NO`/empty means aggregation happens locally, over the whole table |
| `STRING_FUNCTIONS`, `NUMERIC_FUNCTIONS`, `TIMEDATE_FUNCTIONS` | Which functions the engine understands at all |
| `SUBQUERIES`, `OUTER_JOINS` | Usually `NO` on SaaS connectors |
| **`SUPPORTSENHANCEDSQL`** | **The most important row.** `true` means the driver *emulates* everything above that the API cannot do — so unsupported SQL still "works", just locally and at unbounded cost |
| `SQL_CAP` | Operation-level capabilities (select/insert/update/delete/orderby/limit/bulk*) |
| `SUPPORTS_BATCH_OPERATIONS` | Whether batch is real or looped |

Real example — Salesforce Marketing Cloud 2026:

```
SUPPORTED_OPERATORS   =, >, <, >=, <=, <>, !=, IN, AND, OR
GROUP_BY              NO
COUNT                 NO
AGGREGATE_FUNCTIONS   (empty)
SUBQUERIES            NO
STRING_FUNCTIONS      ASCII,CHAR,CONCAT,LEFT,LTRIM,REPLACE,RIGHT,RTRIM,SOUNDEX,SPACE,SUBSTRING
TIMEDATE_FUNCTIONS    CURRENT_DATE,CURRENT_TIMESTAMP,MONTH,YEAR
SUPPORTSENHANCEDSQL   true
```

Read that and you have a strong hypothesis: `LIKE`, `NOT IN`, `BETWEEN`, `GROUP BY`, `HAVING`,
`COUNT`, `SUM` and every date function beyond `MONTH`/`YEAR` are likely evaluated **locally**. That
is not a bug. The bug is what happens next.

> **`sys_sqlinfo` is a floor, not the contract.** Operators absent from `SUPPORTED_OPERATORS` are
> sometimes pushed anyway. On the Jira driver `IN` is *not* declared, yet
> `WHERE ProjectKey IN ('DND','DUM')` compiles to `jql=project IN ("DND","DUM")` with
> `maxResults` equal to the `TOP` — fully pushed. So treat the declaration as the minimum you can
> rely on and **always confirm by probing**: an undeclared-but-pushed operator is a documentation
> gap worth reporting, and assuming it is client-side would have you report a perf bug that does
> not exist.

**Then, for a Tier A table, read the RSD.** Per column it declares `other:filters`,
`other:supportOrderBy`, `other:filterName`, `other:isinsertable`, `other:isupdateable`,
`other:IsRequired`, `xs:type`. Each one is a testable claim — see
[`sql-surface.md`](sql-surface.md) for the claim-to-check mapping.

---

## Phase 2 — Probe what is actually sent

For each construct, run a small query and read the request. **Read `req_body`, not just the URL** —
many APIs (SFMC included) send the filter in a POST body and the URL query string is empty.

```
execute_query  SELECT TOP 3 Id, Name FROM Assets WHERE TypeId = 207
   -> _meta: capture_from=X, capture_to=Y, capture_entries=1
   -> read bytes X..Y of mitm_log_path, look at method / url / req_body
```

### `pageSize` is the tell

This is the single most useful signal in the whole exercise. Compare the page size in the request
against the `TOP`/`max_rows` you asked for:

| What you see | What it means |
|---|---|
| `pageSize` == your `TOP` | fully pushed — filter, projection and limit all reached the API. Cheap. |
| `pageSize` == the connector maximum (e.g. 500) | **the driver is scanning.** Something is being evaluated locally, so it cannot honour your `TOP` until it has looked at everything |
| `capture_entries` climbing with row count | pagination — cost scales with table size, not result size |
| `capture_entries: 0` | answered entirely locally, no backend request at all |

Verified on SFMC `Assets`:

```
WHERE TypeId = 207          (declared)     {"fields":["id","name"],
                                            "query":{"property":"assettype.id",
                                                     "simpleOperator":"equal","value":207},
                                            "page":{"pageSize":3,"page":1}}
                                           -> filter pushed using the RSD's own filterName,
                                              projection pushed, pageSize == TOP 3.  1 request.

WHERE Name LIKE 'B%'        (undeclared)   {"fields":["id","name"],
                                            "page":{"pageSize":500,"page":1}}
                                           -> no query block. pageSize jumped to 500.
                                              It returned in 3s only because matches happened
                                              to be on page 1.
```

Note what Phase 2 also proves in the first case: `property: "assettype.id"` is exactly the RSD's
`other:filterName="assettype.id"`. A wrong `filterName` is a classic driver bug and this is how you
catch it — the API silently ignores an unknown filter property and returns everything.

---

### Batch the probes — one tool call, not twenty

A sweep is dozens of queries you already know in advance, so do not fire them one at a time.
`execute_java` runs them all against the live connection in a **single** tool call and — verified —
still returns **one `intercepted_calls` entry per query**, each with its own SQL and duration:

```java
String[] probes = {
    "SELECT COUNT(*) AS v FROM T",
    "SELECT MAX(Amount) AS v FROM T",
    "SELECT COUNT(*) AS v FROM T WHERE Amount > 100"
};
for (String sql : probes) {
    long t0 = System.currentTimeMillis();
    try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
        String val = rs.next() ? rs.getString("v") : "(no rows)";
        __out.append(String.format("%-50s -> %-8s %4dms%n", sql, val, System.currentTimeMillis()-t0));
    } catch (Exception e) {
        __out.append(String.format("%-50s -> ERROR %s%n", sql, e.getMessage()));
    }
}
```

Why it is worth it: one round trip instead of *n*, one response envelope instead of *n*, and the
snippet returns **only the values you need** rather than *n* full row sets. Always wrap each probe
in its own try/catch — otherwise the first failure aborts the whole batch.

**Four limits that decide what to batch and what not to:**

| Limit | Consequence |
|---|---|
| The whole snippet shares one budget (`JDBC_MCP_JAVA_TIMEOUT`, default **30s**) — there is no per-query timeout | A sweep containing scan-prone probes will blow it. **Keep slow or timeout-prone probes as individual `execute_query` calls**, where each gets its own `timeout_seconds` and its own diagnosis |
| `_meta` carries **one merged** `capture_from`/`capture_to` for the batch | Per-query HTTP attribution is lost. If you need it, keep those probes separate — or have the snippet record the capture file's length between queries itself |
| No per-query `max_rows` | The snippet decides what it materializes; read only the columns you assert on |
| `read_only` still applies (it is enforced in the proxy layer) | A batch cannot smuggle a write past the guard |

**Good split in practice:** batch the cheap declared-operator probes, the projection-function
probes, and the per-table reachability checks. Keep separate: anything you expect to scan
(`LIKE`, `NOT LIKE`, `IS NULL`, `GROUP BY`, unfiltered `COUNT`), because those are exactly the ones
that need their own timeout and their own timeout diagnosis.

> **Never try to batch by putting `;` between statements in `execute_query`.** It does not error —
> it silently runs the **first** statement and discards the rest. Verified: `SELECT COUNT(*) AS n
> FROM Rows; SELECT MAX(Amount) AS m FROM Rows` returned only `n` with no warning. That is a
> silent-wrong-answer shape, and the reason `execute_java` is the only real batching route.

---

## Phase 3 — The emulation traps

`SUPPORTSENHANCEDSQL=true` means unsupported SQL still returns an answer. Three things can be wrong
with that answer, in increasing severity.

### 3a. `TOP` stops protecting you

Once any predicate is client-side, the driver must evaluate it over the whole table before it knows
which *n* rows qualify. `TOP 5` does not bound the work.

Measured on SFMC `Assets` with a 45-second budget:

| Query | Result |
|---|---|
| `WHERE TypeId = 207` (pushed) | 5 rows, 1 request, 2.7s |
| `WHERE Name NOT LIKE 'B%'` | **timed out**, 3.3s in execute + 45s paging |
| `WHERE Description IS NULL` | **timed out** |
| `ORDER BY OwnerEmail` (RSD: `supportOrderBy="false"`) | **timed out** |
| `GROUP BY TypeId` (`GROUP_BY=NO`) | **timed out** |

Report the timeout as the finding. Do not raise the budget to make it pass — see the timeout
guidance in `qa-ticket-verification`.

### 3b. The cost depends on the data, not the SQL

`LIKE 'B%'` returned in 3 seconds here and would time out on a table where the first match sits
past row 10,000. **The same query is fast or fatal depending on the environment**, which is why it
passes in dev and pages support in production. When you find a client-side predicate, say so
explicitly even if it returned quickly — "correct, and unbounded" is the honest verdict.

### 3c. The answer can be silently *wrong* — the one that matters most

If a client-side aggregate or predicate is evaluated over a **truncated** scan, the result is wrong
with no error. Check it deliberately:

```
1.  SELECT COUNT(*) ... GROUP BY x        with max_rows well below the table size
2.  the same aggregate with a much larger max_rows / longer timeout
3.  the same aggregate computed a different way (server-side filter + count of rows returned)
```

If (1) and (2) disagree, the aggregate is being computed over whatever was fetched rather than over
the table. That is a **FAIL** and a high-severity ticket: every consumer gets a plausible wrong
number. Also verify `truncated: true` is present whenever the row cap was hit — an aggregate
reported without that flag is indistinguishable from a complete one.

Same test shape applies to client-side `DISTINCT`, `HAVING`, `MIN`/`MAX`, and `ORDER BY` +
`TOP n` (is it the global top *n*, or the top *n* of the first page?).

---

## Phase 4 — Data types

Pushdown is only half of conformance. A value can arrive from the right endpoint and still be wrong.
Check four things per interesting column and require all four to agree:

| # | Source | Check |
|---|---|---|
| 1 | RSD `xs:type` (Tier A) | what the driver declares |
| 2 | `get_metadata` `type_name` / `data_type` | what JDBC reports to the caller |
| 3 | The value in `rows` | what actually comes back |
| 4 | The API docs / `resp_body` in the capture | what the backend actually sent |

What to look for:

- **datetime** — does the value keep fractional seconds and the correct offset? Compare the
  serialized value against `resp_body` in the capture. A driver that reads `2026-08-19T10:20:30.123Z`
  and reports `10:20:30` is losing precision that a customer's incremental sync depends on.
- **decimal / numeric** — verify with `getBigDecimal` in `execute_java`, not the display string.
  `3.14` displaying correctly says nothing about the JDBC type.
- **FLOAT vs REAL** — JDBC maps `FLOAT` to *double*; only `REAL` is single-precision. A driver
  declaring `FLOAT` whose values round at ~7 digits is truncating.
- **boolean** — `true`/`1`/`"Y"` in the API vs what JDBC reports.
- **integers** — anything that can exceed 2^31 must not be `INTEGER`.
- **null vs empty string** — the API's `null`, `""` and missing-field must map distinctly and
  consistently. Check `rows` against `resp_body`.
- **JSON aggregates** — RSD `other:aggregate="/views/html/slots"` means a JSON path extraction.
  If the API changes shape, the column silently becomes null. Compare against `resp_body`.
- **strings** — unicode, embedded quotes/newlines, and values longer than the declared length.

Type mismatches between (1) and (2) are driver bugs. Between (3) and (4) are serialization bugs.
Between (2) and (4) are schema-drift bugs and often the API changed, not the driver.

---

## Phase 5 — Writes and operations

Only on a **write-enabled session** (`read_only: false`) — and only with the engineer's agreement.

- Every `other:IsRequired="true"` column: INSERT without it must fail **at the driver**, with a
  clear CData-layer message, not as a backend 400.
- `other:isinsertable="false"` / `other:isupdateable="false"`: specifying it must be rejected or
  ignored, never silently written.
- Missing `<rsb:script method="POST">` (or `SQL_CAP` lacking `insert`): INSERT must fail cleanly.
- UPDATE/DELETE without a WHERE on the key: confirm the driver does not fan out into one API call
  per row unless that is genuinely the API's only option — check `capture_entries` against rows.
- Batch: `SUPPORTS_BATCH_OPERATIONS=YES` means `executeBatch` should collapse into bulk calls.
  `batch_size` in `intercepted_calls` against `capture_entries` tells you whether it did.
- Read back every write and clean up what you created.

---

## Coverage — you cannot test everything, so be deliberate

SFMC alone has **108 tables**. A full construct sweep across all of them is thousands of queries,
most of them full scans. That is neither affordable nor useful.

**Do this instead:**

1. **Once per driver** — `sys_sqlinfo`, and the construct sweep on **one representative table**
   (prefer a Tier A table with a datetime column, a key, and both filterable and non-filterable
   columns). The SQL surface is a driver-level property; it does not change per table.
2. **Per table** — only what is table-specific: the RSD's own `filters` / `supportOrderBy` /
   `filterName` claims, required columns, and declared types. Cheap, and where the real drift is.
3. **Prioritise tables** by: the one in the ticket, tables with `other:IsTimeCheckColumn`
   (incremental sync depends on them), tables with JSON aggregates, and the widest table.
4. **Always project explicit columns.** `SELECT *` on a wide table costs many times more and the
   projection pushdown (`fields:[...]`) is itself worth verifying.
5. **Keep `max_rows` small** — 3 to 20. You are testing the request, not harvesting data. The
   exception is Phase 3c, where a deliberately larger cap is the whole point.

State your coverage in the report. "Surface established on `Assets`; per-table claims checked on 6
of 108 tables, chosen for X" is a useful result. "Tested the driver" is not.

---

## Verdict

Per check, one of:

- **PASS** — declared, pushed, correct result, types agree.
- **PASS (client-side)** — not pushed, but correctly evaluated and the API genuinely does not
  support it. Record the cost. Not a bug; it *is* a documentation and expectation issue.
- **FAIL — missed pushdown** — the API supports it, the driver did not push it. Perf bug; include
  the API doc reference and the observed `pageSize`/`capture_entries`.
- **FAIL — wrong request** — pushed with the wrong field name, operator or shape. Include the
  `req_body` and the RSD's `filterName`.
- **FAIL — wrong answer** — the highest severity. Client-side evaluation over a truncated scan, a
  precision loss, or a type mismatch. Include both the driver value and the `resp_body` value.
- **NEEDS-INVESTIGATION** — could not be determined: no API doc, no capture, or the probe itself
  timed out before proving anything.

Record every check with `record_check` so `get_test_report` carries the whole sweep. Findings that
warrant a ticket should be drafted in chat — this skill never posts to Jira or Azure DevOps.

---

## Worked reference

[`sql-surface.md`](sql-surface.md) holds the full construct matrix — every operator, aggregate,
string and date function, with the SQL to run, what to look for in the request, and the SFMC
results as a verified example.
