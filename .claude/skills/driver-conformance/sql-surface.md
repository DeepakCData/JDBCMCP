# The SQL surface matrix

Every construct, the SQL to probe it with, and what to look for. Work down the list on **one
representative table** to establish the driver's surface, then use the per-table section for
table-specific claims.

Throughout: substitute your table and columns, keep `TOP` small, and read `req_body` from the
`capture_from`–`capture_to` range — **not** the URL, and never the log from the top.

Shorthand used below:

- **pushed** — the construct appears in the request; `pageSize` equals your `TOP`
- **local** — no trace of it in the request; `pageSize` jumps to the connector maximum
- **declared** — listed in `sys_sqlinfo` (`SUPPORTED_OPERATORS` etc.) or in the RSD's
  `other:filters` for that column

---

## 1. Comparison operators

Run each against a **filterable** column (RSD `other:filters` lists it) and then against a
**non-filterable** one. The difference between the two is the point.

| Construct | Probe |
|---|---|
| `=` | `SELECT TOP 3 <cols> FROM T WHERE <col> = <v>` |
| `!=` / `<>` | `... WHERE <col> != <v>` |
| `>` `>=` `<` `<=` | `... WHERE <numeric_or_date> > <v>` |
| `IN` | `... WHERE <col> IN (<v1>, <v2>)` |
| `NOT IN` | `... WHERE <col> NOT IN (<v1>, <v2>)` |
| `BETWEEN` | `... WHERE <numeric_or_date> BETWEEN <v1> AND <v2>` |
| `LIKE` prefix | `... WHERE <str> LIKE 'B%'` |
| `LIKE` contains | `... WHERE <str> LIKE '%B%'` |
| `LIKE` suffix | `... WHERE <str> LIKE '%B'` |
| `NOT LIKE` | `... WHERE <str> NOT LIKE 'B%'` |
| `IS NULL` | `... WHERE <nullable> IS NULL` |
| `IS NOT NULL` | `... WHERE <nullable> IS NOT NULL` |
| `AND` | `... WHERE <a> = <v> AND <b> = <v>` |
| `OR` | `... WHERE <a> = <v> OR <b> = <v>` |
| mixed pushable + local | `... WHERE <pushable> = <v> AND <str> LIKE 'B%'` |

**Check for each:** is it pushed or local? Does that match `SUPPORTED_OPERATORS` and the RSD? Is
the request's property name the RSD's `other:filterName`? Are the rows actually correct?

**The mixed case is the most informative one in this table.** A good driver pushes the pushable
half and filters the rest locally, so `pageSize` stays bounded and the scan is narrowed. A driver
that gives up and scans everything the moment one predicate is unsupported is leaving real
performance on the table — worth a ticket even though the answer is right.

### Negation traps

`!=`, `NOT IN`, `NOT LIKE` and `IS NOT NULL` are where correctness slips. Verify explicitly that
rows where the column **is null** are handled as the SQL standard requires: `col != 'x'` must not
return rows where `col IS NULL`. Client-side emulation gets this wrong more often than server-side
filtering does.

---

## 2. ORDER BY, TOP, DISTINCT

| Construct | Probe | Watch for |
|---|---|---|
| ORDER BY, supported col | `... ORDER BY <col> DESC` | sort in the request; `pageSize` == TOP |
| ORDER BY, `supportOrderBy="false"` | `... ORDER BY <that_col>` | expect local; expect a full scan |
| ORDER BY multiple | `... ORDER BY <a>, <b> DESC` | is the *second* key pushed, or only the first? |
| ORDER BY + TOP | `SELECT TOP 5 ... ORDER BY <col>` | **is it the global top 5, or the top 5 of page 1?** |
| DISTINCT | `SELECT DISTINCT <col> FROM T` | almost always local; correct over the whole table? |
| TOP alone | `SELECT TOP 3 ...` | `pageSize` should be 3 |
| No TOP, small `max_rows` | `SELECT <cols> FROM T` with `max_rows: 5` | does `pageSize` follow `max_rows`, or fetch 500? |

`ORDER BY + TOP` deserves its own attention: if the sort is local and the scan is capped, "top 5"
becomes "the 5 highest of however many rows we happened to read". Cross-check by asking for the
same query with a much larger cap and confirming the same 5 rows come back.

---

## 3. Aggregates and GROUP BY

Check `sys_sqlinfo` first: `COUNT`, `GROUP_BY` and `AGGREGATE_FUNCTIONS` are usually `NO`/empty on
SaaS connectors, which means **all of this is computed locally over a full scan.**

| Construct | Probe |
|---|---|
| `COUNT(*)` unfiltered | `SELECT COUNT(*) FROM T` |
| `COUNT(*)` filtered on a pushable column | `SELECT COUNT(*) FROM T WHERE <pushable> = <v>` |
| `COUNT(col)` vs `COUNT(*)` | do they differ correctly on a nullable column? |
| `SUM` / `AVG` / `MIN` / `MAX` | `SELECT SUM(<num>), AVG(<num>), MIN(<num>), MAX(<num>) FROM T` |
| `GROUP BY` | `SELECT <col>, COUNT(*) FROM T GROUP BY <col>` |
| `GROUP BY` + `HAVING` | `... GROUP BY <col> HAVING COUNT(*) > 1` |
| `GROUP BY` multiple | `... GROUP BY <a>, <b>` |
| aggregate + `ORDER BY` on it | `... GROUP BY <col> ORDER BY COUNT(*) DESC` |

**The correctness test that matters** (Phase 3c of the skill): run the same aggregate twice, once
with a small `max_rows` and once with a large one. If the numbers differ, the aggregate is being
computed over the fetched subset rather than the table — a silent wrong answer, high severity.

An unfiltered `COUNT(*)` on a large SaaS table will usually **time out**, and that is the correct
finding to report. Prefer a filtered count for existence checks:
`SELECT COUNT(*) FROM T WHERE <key> = <v>`.

---

## 4. Functions

Compare against `sys_sqlinfo`'s `STRING_FUNCTIONS`, `NUMERIC_FUNCTIONS`, `TIMEDATE_FUNCTIONS`. A
function in the list is understood by the engine; that is **not** the same as pushed to the API.
Most are applied locally to rows already fetched — which is cheap in the projection and expensive
in a predicate.

| Group | Probe in the projection | Probe in the predicate |
|---|---|---|
| String | `SELECT TOP 3 SUBSTRING(<s>,1,3), UPPER(<s>), LEN(<s>), CONCAT(<s>,'x'), LTRIM(<s>), REPLACE(<s>,'a','b') FROM T` | `... WHERE SUBSTRING(<s>,1,1) = 'B'` |
| Numeric | `SELECT TOP 3 ABS(<n>), ROUND(<n>,2), FLOOR(<n>), CEILING(<n>) FROM T` | `... WHERE ROUND(<n>,0) = 5` |
| Date | `SELECT TOP 3 YEAR(<d>), MONTH(<d>), CURRENT_TIMESTAMP FROM T` | `... WHERE YEAR(<d>) = 2026` |
| Date arithmetic | `SELECT TOP 3 DATEADD(day,-7,<d>), DATEDIFF(day,<d>,CURRENT_TIMESTAMP) FROM T` | `... WHERE <d> > DATEADD(day,-7,CURRENT_TIMESTAMP)` |
| Casting | `SELECT TOP 3 CAST(<n> AS VARCHAR), CAST(<s> AS INT) FROM T` | — |
| `NULL` handling | `SELECT TOP 3 ISNULL(<nullable>,'fallback'), COALESCE(<a>,<b>) FROM T` | — |

**The predicate column is where the cost is.** A function wrapped around a filterable column
usually defeats pushdown entirely — `WHERE YEAR(CreatedDate) = 2026` becomes a full scan, while
`WHERE CreatedDate >= '2026-01-01' AND CreatedDate < '2027-01-01'` pushes. If the driver *can*
rewrite the first into the second, verify it does; if it cannot, that is a legitimate finding and a
useful note for customers.

A function not in the `sys_sqlinfo` list should fail with a clear CData-layer error, not silently
return something odd. Try one deliberately.

---

## 5. Complex queries

| Construct | Probe | Expectation |
|---|---|---|
| Subquery in WHERE | `SELECT ... WHERE <k> IN (SELECT <k> FROM T2 WHERE ...)` | `SUBQUERIES=NO` → local, or a clear error |
| JOIN | `SELECT a.<x>, b.<y> FROM T a JOIN T2 b ON a.<k> = b.<k>` | usually local: two scans then a local join. Count `capture_entries` |
| LEFT JOIN | same with `LEFT JOIN` | `OUTER_JOINS=NO` → local or error |
| UNION | `SELECT <c> FROM T UNION SELECT <c> FROM T2` | local |
| CASE | `SELECT TOP 3 CASE WHEN <n> > 5 THEN 'hi' ELSE 'lo' END FROM T` | local projection, cheap |
| Nested filter + sort + limit | `SELECT TOP 5 <cols> FROM T WHERE <pushable> = <v> AND <str> LIKE '%x%' ORDER BY <sortable> DESC` | the realistic customer query — check how much survives pushdown |
| Aliased / quoted identifiers | `SELECT <col> AS [My Col] FROM [T]` | use `IDENTIFIER_QUOTE_OPEN_CHAR` from `sys_sqlinfo` |
| Parameterised | `execute_prepared` with `?` on a pushable column | the bound value must reach the request; check `params` in the trace |

The last two rows matter more than they look. Prepared statements are how customers actually query,
and a driver that pushes a literal but not a bound parameter turns every parameterised query into a
full scan.

---

## 6. Per-table claims (Tier A only)

For each table with an RSD, these come straight from the file and are cheap to verify:

| RSD attribute | Verify |
|---|---|
| `other:filters="..."` | each listed operator pushes; **operators not listed do not** |
| absent `other:filters` | filtering that column is local — confirm, and check whether the API supports it |
| `other:supportOrderBy="true|false"` | ORDER BY pushes / does not |
| `other:filterName="owner.id"` | the request's property name is exactly this, not the SQL column name |
| `other:internalname` | the response field the value is read from |
| `other:aggregate="/views/html/slots"` | JSON path still resolves; compare `rows` against `resp_body` |
| `other:isJsonArray="true"` | returned as a JSON array, consistently |
| `other:selectsingle="true"` | `WHERE <key> = <v>` uses the single-item endpoint — **one** request |
| `key="true"` | reported as a key by `get_metadata` |
| `readonly="true"` / `isinsertable` / `isupdateable` | writes rejected or ignored, never silent |
| `other:IsRequired="true"` | INSERT without it fails at the driver |
| `xs:type` | matches `get_metadata`, the returned value, and the API docs |
| `other:IsTimeCheckColumn="true"` | incremental sync column — datetime precision here is critical |
| `<rsb:set attr="XPath">` | response envelope unchanged; a change means zero rows with no error |
| missing `<rsb:script method="POST|MERGE|DELETE">` | that operation fails cleanly |

---

## 7. Verified example — SFMC 2026, `Assets`

Declared surface:

```
SUPPORTED_OPERATORS   =, >, <, >=, <=, <>, !=, IN, AND, OR
GROUP_BY / COUNT      NO / NO
AGGREGATE_FUNCTIONS   (empty)
STRING_FUNCTIONS      ASCII,CHAR,CONCAT,LEFT,LTRIM,REPLACE,RIGHT,RTRIM,SOUNDEX,SPACE,SUBSTRING
TIMEDATE_FUNCTIONS    CURRENT_DATE,CURRENT_TIMESTAMP,MONTH,YEAR
SUPPORTSENHANCEDSQL   true
```

Observed, with a 45-second budget and `TOP 3`–`TOP 5`:

| Query | Requests | Result | Request body |
|---|---|---|---|
| `WHERE TypeId = 207` | 1 | 5 rows, 2.7s | `query:{property:"assettype.id",simpleOperator:"equal",value:207}`, `pageSize:3` |
| `WHERE Name LIKE 'B%'` | 1 | 3 rows, 3.2s | **no query block**, `pageSize:500` |
| `WHERE TypeId NOT IN (207,208)` | 1 | 5 rows, 3.8s | not pushed |
| `WHERE TypeId IN (207,208)` | — | **timed out** | declared supported — investigate |
| `WHERE Name NOT LIKE 'B%'` | — | **timed out** | local, full scan |
| `WHERE Description IS NULL` | — | **timed out** | local, full scan |
| `ORDER BY CreatedDate DESC` | 1 | 5 rows, 1.1s | RSD `supportOrderBy="true"` — pushed |
| `ORDER BY OwnerEmail` | — | **timed out** | RSD `supportOrderBy="false"` — as declared |
| `GROUP BY TypeId` | — | **timed out** | `GROUP_BY=NO` |
| `SUBSTRING(Name,1,3)` in projection | 1 | 5 rows, 1.0s | local projection, cheap |
| `YEAR(CreatedDate)` in projection | 1 | 5 rows, 0.9s | local projection, cheap |
| `SELECT COUNT(*) FROM sys_tables` | 4 | 108, **10.7s** | metadata discovery is not free either |

Two things to take from this table:

**`IN` is declared in `SUPPORTED_OPERATORS` and yet timed out, while the undeclared `NOT IN`
returned in under 4 seconds.** That inversion is exactly the kind of finding this skill exists to
surface — it needs the request body inspected to confirm whether `IN` was pushed and ignored, or
never pushed at all. Treat it as **NEEDS-INVESTIGATION** until the body is read, then file it.

**`pageSize` told the story every time.** 3 when everything pushed, 500 the moment anything did
not. Check it before anything else.
