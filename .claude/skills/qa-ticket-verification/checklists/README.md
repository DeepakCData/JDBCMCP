# Operation Testing Checklists

## How checklists relate to user instructions

**Checklists are a floor, not a ceiling.**

User instructions always come first. The checklist is what you run in addition — to fill gaps,
ensure nothing obvious is missed, and give consistent baseline coverage when instructions don't
spell everything out. It is never a boundary on what can be tested.

- If the user says "test selects on this table", run the select checklist **plus** anything the
  user's context implies (ticket behaviour, known edge cases, anything they mention).
- If the user says "focus only on LIKE pushdown", do that — and use the checklist to ensure you
  also cover the surrounding core that makes the result trustworthy.
- If the user gives detailed test cases, execute those first, then use the checklist to fill
  any remaining gaps. Never substitute the checklist for instruction-driven tests.
- Never say "that's outside the checklist" as a reason not to test something.

---

## When to load a checklist

Load the matching checklist when the instruction is vague or partial — e.g.
"do select tests on this table", "test inserts", "check batch ops", "verify the stored procs".

| Instruction sounds like | Load |
|---|---|
| "select tests", "read tests", "query this table", "test filters/sorting/aggregates" | [select-testing.md](select-testing.md) |
| "insert tests", "test creates", "can we write rows" | [insert-testing.md](insert-testing.md) |
| "update tests", "test modifying records" | [update-testing.md](update-testing.md) |
| "delete tests", "test removals" | [delete-testing.md](delete-testing.md) |
| "CUD tests", "write tests", "DML tests" | insert → update → delete, in that order (insert creates the rows the other two use) |
| "batch tests", "bulk insert", "executeBatch", "BatchSize" | [batch-operations-testing.md](batch-operations-testing.md) |
| "stored proc tests", "test the procedures", "EXEC tests" | [stored-procedures-testing.md](stored-procedures-testing.md) |

A ticket-driven QA run (the main SKILL.md phases) can also pull these in: when the ticket says
"the fix affects INSERT" but gives no explicit test cases, the insert checklist is the baseline
coverage — derive ticket-specific tests on top of it, and trim tiers that are irrelevant to the diff.

---

## Shared rules — apply to every checklist

These are stated once here so the per-operation files stay focused. **Read this section first.**

### Session discipline

1. `load_driver` → `connect` → tests → `get_test_report` → `disconnect`. Always.
2. **SELECT-only runs connect with `read_only: true`.** Any CUD/batch/proc-with-side-effects run
   needs `read_only: false` — say so explicitly before connecting, and confirm with the engineer
   if the target is shared or production-like data.
3. After `connect`, report the capture channel (proxy vs driver log) per SKILL.md — non-negotiable.
4. Label every check with a `criterion` so `get_test_report` produces a per-test verdict.

### Metadata first, always

Before the first query, run `get_metadata` (`metadata_style: "cdata"` on CData drivers) for the
target table(s):

- Confirm the **real table and column names** — never trust names from the instruction verbatim.
- Record for each column: `DataTypeName`, `DataType` (JDBC code), `Length`, `IsKey`,
  `IsNullable`, `IsAutoIncrement`, and (CData) whether the column is **read-only**.
- Note the primary key — nearly every checklist below needs it.
- If the table doesn't appear, check `sys_tables` for the exact spelling/schema before concluding
  it's missing.

This metadata drives test selection: which columns to filter on, which types need boundary tests,
which columns must be excluded from writes.

### Pick the test rows deliberately

- **Reads:** find 1–3 "anchor rows" first (`SELECT <pk>, <a few columns> FROM t LIMIT 3`) and use
  their real values in predicate tests, so every expected result is known in advance.
- **Writes:** operate only on rows **you created in this session** (marker value in a text column,
  e.g. `'MCP_QA_<ticket-or-date>'`) or rows the engineer explicitly designated. Capture a
  **before-image** (`SELECT *` of affected rows) before any UPDATE/DELETE.

### Verify at three layers, not one

Every test verifies all of:

1. **The JDBC result** — row count / update count / returned values / exception.
2. **The data** — a follow-up SELECT proving the effect (row exists / changed / gone, others
   untouched). For cloud drivers this also proves the backend accepted the write, not just the
   driver.
3. **The wire** — `intercepted_calls` on every response, plus the capture channel (mitmproxy
   JSONL or driver log) for HTTP drivers: right verb (GET/POST/PATCH/DELETE), filter pushdown,
   no N+1, no silent SQL rewrite.

### Errors: assert at the CData layer

Negative tests assert on **what the CData driver surfaces**, never on native DB codes: SQLState is
typically `HY000` (not the native one, e.g. Oracle 72000), messages are prefixed/wrapped (e.g.
`STMT ORA-01438: value larger than...`, `HTTP [40002] [invalidRequest]`), and exception types can
change. A negative test **passes** when a clear, accurate CData error comes back; it **fails** when
the operation silently succeeds, silently no-ops, or the error is misleading (wrong SQLState,
mangled message, NPE instead of SQLException).

### Cleanup is part of the test

Write tests end by restoring state: delete rows you inserted, restore before-images you updated,
and re-run a count to prove the table is back to baseline. Report any rows you could not clean up
with their keys. Then `disconnect`.

### Reporting

Finish with `get_test_report` + a chat summary: per-criterion pass/fail, the evidence (SQL +
values) behind each, the capture channel line, and anything skipped and why (e.g. "table is
read-only per metadata — INSERT tier skipped, reported as N/A not FAIL").
