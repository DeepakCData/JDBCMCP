# Stored Procedure Testing Checklist

Use when asked to "test the stored procedures / procs / EXEC" with no further detail. This
checklist is a **baseline** — always execute any specific instructions the engineer gave first,
then use these tiers to fill gaps. If the engineer specifies proc scenarios not listed here, run
them. Shared rules are in [README.md](README.md).

Two very different worlds share this checklist — identify which you're in first:

- **Native DB procs** (Oracle/SQL Server/MySQL/PostgreSQL...): real server-side code — may have
  OUT params, return values, multiple result sets, and side effects on tables.
- **CData connector procs**: driver-exposed **API operations** (e.g. `UploadDocument`,
  `RefreshOAuthAccessToken`, `GetOAuthAuthorizationURL`, download/attachment ops). Their "side
  effect" is an API call — verification means checking the backend object and the HTTP trace,
  and many are **unsafe to fire blindly** (they mutate the account). Read the proc list before
  executing anything.

Connect `read_only: true` for discovery; reconnect `read_only: false` only for procs with side
effects, after confirming with the engineer **which procs may be executed**.

---

## Tier 1 — Discovery & signature verification (always run, read-only)

1. `get_metadata` with `include_procs: "true"`, and on CData drivers query the system views:
   `sys_procedures` (catalog/schema/name) and `sys_procedureparameters`
   (`ProcedureName`, `ColumnName`, `Direction`, `DataTypeName`, `IsRequired`).
2. For each target proc record: **name (exact spelling/schema), every parameter's name,
   direction (IN/OUT/INOUT), type, required-ness**, and — native DBs — cross-check against
   `DatabaseMetaData.getProcedures()` / `getProcedureColumns()` via `execute_java`. The two
   sources must agree; a param missing from one view is a metadata bug.
3. Classify each proc: **read-like** (getters, URL builders — safe), **write-like**
   (upload/create/refresh-token — needs sign-off), **destructive** (delete/purge — only with an
   explicit target from the engineer).

## Tier 2 — Execution of safe procs

Run each read-like proc through **both** call paths — drivers route them differently:

- **SQL EXEC path** via `execute_query`: `EXEC <proc> <param> = '<value>'`
  (CData syntax; native dialects: `CALL proc(...)` / `EXEC proc @p = ...`).
- **CallableStatement path** via `execute_java`:

```java
CallableStatement cs = connection.prepareCall("{call <proc>(?, ?)}");
cs.setString(1, "<in value>");
cs.registerOutParameter(2, java.sql.Types.VARCHAR);
boolean hasRs = cs.execute();
__out.append("out=").append(cs.getString(2));
if (hasRs) { ResultSet rs = cs.getResultSet(); /* iterate, append */ }
```

Verify per proc:

| # | Check | Pass when |
|---|---|---|
| 2.1 | Executes without error | No exception on a correctly-formed call with all required params |
| 2.2 | Result-set shape | Returned columns match documented/metadata output columns; values plausible and typed correctly (`ResultSetMetaData`) |
| 2.3 | OUT params | Every OUT/INOUT param retrievable after `registerOutParameter`, right type, non-garbage value |
| 2.4 | Return value (native) | `{? = call ...}` registered return retrievable |
| 2.5 | Path parity | EXEC path and CallableStatement path give the same result |

## Tier 3 — Side-effect procs (only with sign-off)

For each approved write-like proc:

1. Capture the **before state** of whatever the proc touches (a table row, a file listing, an
   attachment count).
2. Execute with known inputs.
3. Verify the **after state** — the object exists/changed exactly as the inputs said (e.g. after
   `UploadDocument`, SELECT the documents table and find the new file by name/size).
4. Trace check: the proc mapped to the documented API call(s) — right verb, right endpoint,
   inputs present in the body.
5. Clean up the side effect where possible (delete the uploaded doc, etc.).

Native-DB procs that write tables: same before/after discipline on the affected tables, plus
verify the proc's transactional claim (all-or-nothing if it's supposed to be atomic — force a
mid-proc failure only if the ticket is about that).

## Tier 4 — Negatives (safe to run against any proc)

- **Missing required param** → clear CData error naming the parameter (`IsRequired` came from
  Tier 1), not an NPE or a generic failure.
- **Wrong type** for a param (string into a strictly-typed input) → clean conversion error.
- **Nonexistent proc name** → "procedure not found" style error, not a hang.
- **NULL for an optional param** → executes; report how the driver treated it (default vs
  literal NULL).
- Assert all of these at the **CData layer** (per README): `HY000`/`HTTP [...]`-prefixed
  messages, not the native code the backend would emit.

## Tier 5 — Multiple result sets & cursors (native DBs, when applicable)

- A proc returning 2+ result sets: `execute()` → iterate `getMoreResults()` — each RS has the
  right shape; count of result sets matches the proc body.
- Update-count interleaving: procs mixing SELECTs and DML — `getUpdateCount()`/`getMoreResults()`
  walk terminates correctly (no infinite loop, ends with both `false` and −1).

## Verdict guidance

- **FAIL**: signature mismatch between metadata views, required param not enforced, OUT param
  wrong/unretrievable, EXEC vs CallableStatement disparity, side effect differing from inputs,
  misleading errors, result-set walk that never terminates.
- **N/A**: connector exposes no procedures (empty `sys_procedures`) — verify with both metadata
  paths before reporting.
- Report as **observed behaviour**: NULL-for-optional handling, which call syntaxes the driver
  accepts.
