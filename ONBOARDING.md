# JDBC MCP Server — Agent Onboarding & Setup Guide

> **This document is addressed to you, the Claude agent.** A user has handed you this file because
> they want the JDBC MCP server working on their machine. Your job is to **drive the setup
> end-to-end**: detect what's already present, figure out what's missing, generate the exact
> commands for *their* OS and paths, ask permission, and only then make changes. Do not assume the
> environment matches the author's — verify everything.

---

## What this server is

A local MCP server (Java, stdio transport) that lets you open real JDBC connections to databases
and CData connectors (SAP ERP, Salesforce, SharePoint, ServiceNow, Snowflake, and 30+ more), run
SQL, inspect metadata, capture the driver's HTTP traffic via an auto-managed mitmproxy, and do
evidence-backed QA on Jira tickets (via the bundled `qa-ticket-verification` skill).

When fully set up, the user gets a `jdbc-platform` MCP server with 15 tools and the QA skill
available in their Claude Code sessions.

---

## Your operating rules

Follow these strictly while running the setup:

0. **Read the skill first.** Before anything else, read
   `.claude/skills/qa-ticket-verification/SKILL.md` end-to-end. It is the authoritative operational
   guide for this project — it explains the connection/proxy model, the tool workflow, capture
   channels, expected behaviours (e.g. the proxy-port override), CData internals, and pitfalls.
   Understanding it is what lets you set things up and run them correctly. Do this before the
   preflight.
1. **Detect before you act.** Run the preflight checks (Phase 0) first. Never assume a tool is
   installed or a path exists — confirm it.
2. **Never run a mutating command without explicit permission.** Building, installing packages,
   editing config, registering the MCP server, copying the skill — each needs a clear "yes" from
   the user. Present the exact command, explain what it does, then wait.
3. **Resolve real values — never hardcode the author's.** The absolute repo path, the user's home
   directory, their OS and shell may all differ. Detect the current working directory and OS, and
   substitute real values into every command you generate.
4. **Adapt commands to the user's OS and shell.** This file's examples are PowerShell on Windows.
   If the user is on macOS/Linux or a different shell, translate them (`./mvnw` instead of
   `.\mvnw.cmd`, forward slashes, etc.).
5. **Never invent credentials or secrets.** API tokens, PATs, passwords, connection strings,
   driver license paths — you cannot detect or guess these. Ask the user to provide them, and tell
   them exactly what's needed and where to get it.
6. **Report, then proceed.** After the preflight, show the user a status summary (present / missing
   / needs action) before doing anything. Let them decide the order.
7. **Reads don't need permission, writes do.** This repo ships a checked-in
   `.claude/settings.json` that allowlists read-only operations — file/process inspection
   (`Get-ChildItem`, `cat`, `grep`, `git status/log/diff`, …) and every read/list/search tool on
   `jdbc-platform`, the `atlassian` Jira server, and the `azure-devops` server. Use these freely
   and just tell the user what you're reading — don't ask first. Anything that mutates state
   (`execute_update`, `load_driver`, `connect`/`disconnect`, any Jira/ADO create/update/comment/PR
   tool, destructive shell commands) still prompts, and always will — that boundary is
   intentional, not a bug to route around. **The allowlist assumes the companion servers are
   registered under the exact names `atlassian` and `azure-devops`** (Phase 5's commands use
   these). If a user's registration uses a different name, the permission rules won't match and
   they'll see prompts for reads too — tell them to either re-register under the standard name or
   add matching `mcp__<their-server-name>__*` rules to their own `.claude/settings.local.json`.

---

## Phase 0 — Preflight detection (run these now)

Run each check and record the result. Translate the command to the user's actual shell if needed.

| # | What to check | Command (Windows / PowerShell) | Pass condition |
|---|---|---|---|
| 1 | Java JDK 17+ | `java -version` | Reports version 17 or higher |
| 2 | The built server JAR | check `target/jdbc-mcp-server-1.0-SNAPSHOT.jar` exists | File present |
| 3 | mitmproxy | `mitmdump --version` | Reports a version |
| 4 | Python (only if mitmproxy missing) | `python --version` | Present, for `pip install` |
| 5 | Current repo absolute path | `Get-Location` (or `pwd`) | Note it — needed for MCP registration |
| 6 | The QA skill in the repo | check `.claude/skills/qa-ticket-verification/SKILL.md` exists | File present |
| 7 | Is `jdbc-platform` already registered? | `claude mcp list` | Note if already present |
| 8 | Jira (Atlassian) MCP registered? | same `claude mcp list` output | An `atlassian` (or equivalent Jira) server present |
| 9 | Azure DevOps MCP registered? | same `claude mcp list` output | An `azure-devops` server present |

Checks 8–9 are the **companion servers the QA skill depends on** — Jira to read the ticket,
Azure DevOps to read the implemented fix. If either is missing, you will offer to register it in
Phase 5. The JDBC server itself works without them.

After running all of these, produce a short status table for the user, e.g.:

```
Java 17+         [ok] found (21.0.2)
Server JAR       [missing] not built yet
mitmproxy        [missing] not installed
QA skill         [ok] present in repo
MCP registered   [missing] not yet
Jira MCP         [missing] not registered — QA skill can't read tickets
Azure DevOps MCP [missing] not registered — QA skill can't read PR diffs
Repo path        C:\Users\<them>\...\jdbc-mcp-server
```

Then walk through only the phases that have gaps.

---

## Phase 1 — Build the server JAR (if missing)

The repo ships a bundled Maven wrapper — no separate Maven install needed.

Command to propose (ask permission first):
```powershell
.\mvnw.cmd -q clean package
```
(macOS/Linux: `./mvnw -q clean package`)

Produces `target/jdbc-mcp-server-1.0-SNAPSHOT.jar`. If the build fails, the usual cause is
`JAVA_HOME` not pointing at a JDK 17+. Surface the actual error to the user — don't guess.

---

## Phase 2 — Install mitmproxy (if missing)

The server auto-manages a local mitmproxy to capture HTTP traffic from CData drivers. If
`mitmdump` isn't on PATH, capture silently falls back to driver-native logging — so this is
**recommended but not strictly required**. Tell the user that tradeoff and let them choose.

Command to propose (ask permission first):
```powershell
pip install mitmproxy
```
Then re-verify with `mitmdump --version`. If `pip` isn't found, the user needs Python first —
tell them, don't try to install Python silently.

---

## Phase 3 — Register the MCP server

**This repo ships a project-scope `.mcp.json`** (launches `java -jar target/jdbc-mcp-server-1.0-SNAPSHOT.jar`
relative to the repo root). If the user opened this folder in Claude Code and approved the
`jdbc-platform` server when prompted, registration is already done — verify with `claude mcp list`
and skip to Phase 6. It only works after Phase 1's build has produced the JAR.

If the user instead wants the server available **outside this repo**, register it at user scope.
Decide scope **with the user**:

- **User scope** (`--scope user`): available in every Claude Code session, any directory. Best for
  daily personal use. Not shared with teammates.
- **Project scope**: the shipped `.mcp.json` — shared with everyone who gets the repo, but only
  active inside this folder.

For user scope, generate the command with the **real absolute path** you detected in Phase 0,
ask permission, then run it:
```powershell
claude mcp add jdbc-platform --scope user -- java -jar "<ABSOLUTE_PATH_TO_REPO>\target\jdbc-mcp-server-1.0-SNAPSHOT.jar"
```

Substitute `<ABSOLUTE_PATH_TO_REPO>` with the path from Phase 0, check #5. On Windows use
backslashes; on macOS/Linux use forward slashes.

Tell the user to **restart Claude Code** afterward so the server loads.

---

## Phase 4 — Install the QA skill (optional)

If the user wants the Jira QA workflow available **outside this repo**, copy the skill to their
personal skills folder. Ask permission, then:

```powershell
Copy-Item -Recurse ".\.claude\skills\qa-ticket-verification" "$env:USERPROFILE\.claude\skills\qa-ticket-verification"
```
(macOS/Linux: `cp -r .claude/skills/qa-ticket-verification ~/.claude/skills/`)

If they only ever QA tickets from inside this repo, skip this — the project-scoped skill already
works here.

---

## Phase 5 — Companion MCP servers + things only the user can provide

The QA skill needs two more MCP servers besides `jdbc-platform`: **Jira (Atlassian)** to read
tickets and **Azure DevOps** to read the implemented fix. **If the preflight (checks 8–9) found
either missing, proactively prompt the user here** — explain what each enables, ask if they want
it registered, and only run the command after a clear "yes". If the user does QA tickets at all,
recommend both. If they decline, note the limitation (the skill will fall back to asking for
ticket details / PR links manually) and move on.

### Jira (Atlassian) MCP server — lets the QA skill read tickets (Phase 1 of the skill)
If no `atlassian` server was found, propose registering Atlassian's official remote MCP server
(OAuth in the browser — no API token needed):
```powershell
claude mcp add --transport sse atlassian https://mcp.atlassian.com/v1/sse
```
After restarting Claude Code, the user completes sign-in via the `/mcp` command. If their
organization blocks the remote server, the alternative is an API-token-based Jira MCP server —
ask for their Atlassian site URL, email, and an API token
(from https://id.atlassian.com/manage-profile/security/api-tokens). Never write a placeholder
token — get the real one or skip.

### Azure DevOps MCP server — lets the QA skill read the fix/PR diff (Phase 2 of the skill)
If no `azure-devops` server was found, ask the user for their **ADO organization name** and a
**Personal Access Token** (generate at `https://dev.azure.com/<org>/_usersSettings/tokens`, with
at least Code: Read and Work Items: Read scopes). Requires Node.js (`npx`) — verify it's present
first. Then propose:
```powershell
claude mcp add azure-devops --env AZURE_DEVOPS_EXT_PAT=<their-PAT> -- npx -y @azure-devops/mcp <their-org> -a env
```
Never invent or hardcode a PAT. If the user skips this, tell them the QA skill will ask them to
paste PR links/diffs manually (or proceed without fix review) when it reaches Phase 2.

### CData driver JARs (required to connect to CData sources — cannot be automated)
The server includes **no** driver JARs — they are licensed separately. Ask the user:
- Which connectors do they need (Salesforce, SAP ERP, SharePoint, etc.)?
- Where are the `.jar` files on their machine? (typical Windows path:
  `C:\Program Files\CData\CData JDBC Driver for <Product> 2025\lib\`)
- If they don't have them, point them to https://www.cdata.com/jdbc/ to license/download.

You'll pass the JAR path to `load_driver` at runtime — you don't install it.

---

## Phase 6 — Verify it works

Once the MCP server is registered and Claude Code restarted, confirm the connection end-to-end:

1. Confirm the server is live — call the `list_sessions` tool. An empty session list means the
   server started correctly.
2. If the user provided a driver JAR and connection string, do a minimal `load_driver` ->
   `connect` -> `list_sessions` round-trip and report the result. **Note:** on `connect` you may
   see a message that proxy settings were overridden to 8889 — that is expected, not an error (see
   "Proxy behavior" below). Pass the connection string exactly as given and do not try to bypass
   the override.

Report a clear final status: what's set up, what's still pending (e.g. "drivers not yet provided"),
and what the user can ask you to do next.

---

# Reference (for the agent during operation)

## The 15 tools

| Tool | What it does |
|---|---|
| `load_driver` | Load a JDBC driver JAR by path |
| `connect` | Open a JDBC connection (returns session_id) |
| `execute_query` | Run a SELECT and return rows |
| `execute_update` | Run INSERT / UPDATE / DELETE |
| `execute_prepared` | Parameterized query with bind variables |
| `execute_java` | Run a Java code snippet against the session |
| `get_metadata` | List tables, columns, types |
| `list_sessions` | Show all active connections |
| `get_usage_stats` | Query/row/token stats for a session |
| `disconnect` | Close a session |
| `record_check` | Record a named QA assertion |
| `assert_query` | Run a query and assert on the result |
| `compare_queries` | Diff two queries for reconciliation |
| `get_test_report` | Produce a pass/fail QA report |
| `export_results` | Export query results to CSV |

## Proxy behavior (no setup needed — it self-manages)

> **Expected behaviour — do NOT try to bypass this.** For HTTP/cloud drivers the server
> **deliberately force-overrides** any `ProxyServer`/`ProxyPort` in the user's connection string
> and points them at its own auto-managed mitmproxy (default `localhost:8889`). If you see a
> message like *"proxy settings are being overridden to 8889"* — even when the connection string
> said `ProxyPort=8888` — **that is correct and by design.** Do not "fix" it by honoring the 8888
> in the string, and do not strip/alter the user's proxy props. Pass the connection string exactly
> as given. The override is how capture works. **If a different proxy port is genuinely needed,
> change it the right way:** set the `JDBC_MCP_MITM_PORT` environment variable before the server
> starts (see the config table above) — never via the connection string.

- **HTTP-based drivers** (Salesforce, SharePoint, ServiceNow, SAP ERP, Google Drive, etc.) — the
  server auto-starts mitmproxy on `localhost:8889` on first `connect`, **force-injects**
  `ProxyServer/ProxyPort/ProxySSLType=TUNNEL/SSLServerCert=*` (overriding any proxy values already
  in the connection string), and logs full request/response to `<system-temp>/jdbc_mcp_proxy.jsonl`.
- **Binary/TCP drivers** (PostgreSQL, MySQL, SQL Server, Oracle, etc.) — no proxy, direct connect.
- **File drivers** (Excel, CSV, JSON) — proxied only if the URI is remote (S3, SharePoint, HTTPS).
- If mitmproxy fails to start, the server falls back to CData driver-native logging automatically.

## Optional configuration (sensible defaults — only change if asked)

| What | Env var | Default |
|---|---|---|
| Query timeout | `JDBC_MCP_QUERY_TIMEOUT` | 30 seconds |
| Max rows returned | `JDBC_MCP_MAX_ROWS` | 1000 |
| Max concurrent sessions | `JDBC_MCP_MAX_SESSIONS` | 50 |
| Proxy port | `JDBC_MCP_MITM_PORT` | 8889 |
| Proxy log path | `JDBC_MCP_MITM_LOG_PATH` | `<temp>/jdbc_mcp_proxy.jsonl` |
| Block all writes | `JDBC_MCP_READ_ONLY` | false |

## Troubleshooting

- **Server not appearing after registration** — confirm the JAR path in the registration is the
  real absolute path; re-run the build; ensure Claude Code was restarted.
- **"Could not load driver"** — wrong JAR path, or the JAR doesn't match the Java version.
- **Proxy/capture not working** — check `mitmdump --version`; if port 8889 is busy set
  `JDBC_MCP_MITM_PORT`; the server still works via driver-native logging fallback.
- **QA skill can't find the ADO PR** — `AZURE_DEVOPS_EXT_PAT` missing/expired, or the Jira ticket
  has no remote link to the PR.
