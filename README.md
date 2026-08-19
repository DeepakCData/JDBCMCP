# JDBC Platform MCP Server

A local MCP server (Java 17, stdio transport) that gives Claude real JDBC access for QA work:
open live connections to databases and CData connectors (SAP ERP, Salesforce, SharePoint,
ServiceNow, Snowflake, and 30+ more), run SQL / prepared statements / Java snippets, inspect
metadata, capture the driver's HTTP traffic through an auto-managed mitmproxy, and produce
evidence-backed pass/fail reports for Jira tickets (via the bundled `qa-ticket-verification`
Claude skill).

**14 tools:** `load_driver`, `connect`, `execute_query`, `execute_update`, `execute_prepared`,
`execute_java`, `get_metadata`, `list_sessions`, `disconnect`, `record_check`,
`assert_query`, `compare_queries`, `get_test_report`, `export_results`.

**Permissions:** the checked-in [.claude/settings.json](.claude/settings.json) allowlists every
read/list/search tool on `jdbc-platform`, Jira (`atlassian`), and Azure DevOps (`azure-devops`),
plus read-only shell commands — so Claude reads freely without prompting. Writes
(`execute_update`, `connect`/`disconnect`, any Jira/ADO create/update/comment tool) always still
prompt. This assumes the companion servers are registered under the names `atlassian` and
`azure-devops` (see ONBOARDING.md Phase 5) — a different registration name means those specific
reads will prompt too, until matching rules are added locally.

---

## Quick start (new machine)

### The easy way — let Claude set it up

Open this folder in Claude Code and say:

> Set this project up on my machine — follow ONBOARDING.md

[ONBOARDING.md](ONBOARDING.md) is an agent-facing runbook: Claude detects what's missing
(JDK, build, mitmproxy, MCP registration), proposes the exact commands for your OS, and asks
before changing anything.

### The manual way

Prerequisites: **JDK 17+** on PATH. Optional but recommended: **mitmproxy**
(`pip install mitmproxy`) for HTTP traffic capture — without it the server falls back to
CData driver-native logging automatically.

```powershell
# 1. Build (bundled Maven wrapper — no Maven install needed)
.\mvnw.cmd -q clean package          # macOS/Linux: ./mvnw -q clean package

# 2. Register — pick ONE:
#    a) Project scope (zero commands): this repo ships .mcp.json, so just open the folder
#       in Claude Code and approve the "jdbc-platform" server when prompted.
#    b) User scope (works from any directory):
claude mcp add jdbc-platform --scope user -- java -jar "<ABSOLUTE_PATH_TO_THIS_REPO>\target\jdbc-platform-1.0-SNAPSHOT.jar"

# 3. Restart Claude Code, then ask Claude to call list_sessions to confirm it's live.
```

Note: the project-scope `.mcp.json` launches `java -jar target/...` — the build (step 1) must
have run at least once, and Java must be on PATH.

### What you must provide yourself

- **CData driver JARs** — licensed separately, not included. Typical location:
  `C:\Program Files\CData\CData JDBC Driver for <Product> 2025\lib\`. Get them at
  <https://www.cdata.com/jdbc/>. You pass the JAR path to `load_driver` at runtime.
- **Jira (Atlassian) MCP + Azure DevOps MCP** — needed for the QA-ticket skill's investigation
  phases (reading the ticket and the fix's PR diff). The ONBOARDING.md flow detects whether they
  are registered and offers to set them up — Atlassian via its remote OAuth server, Azure DevOps
  via `npx @azure-devops/mcp` with your org name and a PAT (Code: Read + Work Items: Read).
  If you skip them, the QA skill asks for ticket details / PR links manually, or can proceed
  without PR review at your choice.

---

## Drivers that need two JARs

Most connectors load from a single CData JAR. A connector that wraps a vendor driver does not:
Oracle OCI and other native/thin wrappers need the vendor's own JDBC or client JAR on the same
classloader. Pass the CData JAR as `jar_path` and the vendor JAR in `extra_jars`:

```json
{ "jar_path": ".../lib/cdata.jdbc.oracleoci.jar", "extra_jars": ["C:/path/to/ojdbc11.jar"] }
```

Do **not** add vendor JARs to the server's own launch classpath instead. Drivers are loaded at
runtime per call, and a driver sitting on the launch classpath is rejected rather than registered —
otherwise `load_driver` could report a completely different driver as loaded. The `load_driver`
response names `driver_jar`, the JAR that actually defined the registered class, so what is under
test is never in doubt. When a connector needs a companion JAR and does not have one, `connect`
fails with a missing class and the error says exactly that.

## Proxy & traffic-capture rules (read before your first connect)

The server decides proxying **automatically per driver** — you never configure it per
connection, and agents must pass connection strings **exactly as given**:

| Driver category | Behaviour |
|---|---|
| **HTTP/cloud** (Salesforce, SAP ERP, SharePoint, ServiceNow, Google Drive, Snowflake, BigQuery, …) | mitmproxy is auto-started on `localhost:8889`; `ProxyServer/ProxyPort/ProxySSLType=TUNNEL/SSLServerCert=*` are **force-injected**, overriding any proxy values already in the connection string |
| **Binary/TCP** (PostgreSQL, MySQL, SQL Server, Oracle, …) | Direct connection; stale `Proxy*` props are stripped |
| **File** (Excel, CSV, JSON, …) | Proxied only when the data location is a remote URI |

- Seeing *"proxy settings overridden to 8889"* even though your string said `ProxyPort=8888`
  is **by design** — that override is how capture works. Never "fix" it via the connection
  string; if you genuinely need another port, set `JDBC_MCP_MITM_PORT` before the server starts.
- Override the per-driver auto-decision only with the `use_proxy: "always" | "never"` connect
  parameter. `proxy_host`/`proxy_port` are reserved for an external corporate proxy.
- **Automatic fallback:** if mitmproxy can't start or the driver bypasses it, the server
  reconnects without proxy and injects CData driver-native logging (`Logfile` + `Verbosity=5`)
  instead. The `connect` response always tells you what happened.

### Where the logs are

| Channel | When active | Location |
|---|---|---|
| mitmproxy JSONL (full request/response) | `proxy_applied=true`, no fallback | `mitm_log_path` — `<system-temp>/jdbc_mcp_proxy.jsonl` (override: `JDBC_MCP_MITM_LOG_PATH`) |
| CData driver log | `proxy_fallback=true` or binary/file drivers with `verbose_log` | `logfile_path` in the `connect` response |

Every `connect` response reports `driver_category`, `proxy_applied`, `mitm_status`,
`proxy_fallback`, `proxy_fallback_reason`, and `logfile_path` — the agent is required to state
the active capture channel in chat before running queries.

### What the capture log does not contain

Credential headers (`Authorization`, `Cookie`, `X-Api-Key`, …) are replaced with a length plus a
truncated SHA-256 — enough to tell two tokens apart or spot a refresh, without writing a live bearer
token to a file in the system temp directory. Bodies are capped at `JDBC_MCP_MITM_MAX_BODY` (64 KB)
with `resp_body_truncated` marking what was cut; an uncapped body turned a single download into a
100 MB log line. Override the header list with `JDBC_MCP_MITM_REDACT_HEADERS`.

### Log hygiene — read the capture from `mitm_log_offset`, not from the top

The JSONL capture is **append-only and shared by every session** in a server run, so reading it
from the start returns some earlier session's traffic. `connect` returns `mitm_log_offset`: the
file's byte length immediately before that session connected. Start there. (`intercepted_calls`
on query responses is built in memory per call, so it is never affected by the log's size.)

Both log kinds are cleaned up automatically, since a `Verbosity=5` driver log can reach hundreds
of MB and the driver rotates its own log at 100 MB without ever pruning:

- **Server start** — the capture JSONL is rotated aside (`jdbc_mcp_proxy-<stamp>.jsonl`) so each
  run begins empty, then a sweep deletes `jdbc_mcp_*` logs older than
  `JDBC_MCP_LOG_RETENTION_DAYS` and trims oldest-first until the total fits
  `JDBC_MCP_LOG_MAX_TOTAL_MB`. Both actions are reported on stderr at boot.
- **`disconnect`** — deletes that session's driver log and reports `logfile_bytes_freed`. Pass
  `keep_logfile: true` when the log is evidence you need to keep. Idle-evicted sessions get the
  same treatment.
- Set `JDBC_MCP_LOG_SWEEP=false` to disable both rotation and the sweep.

Nothing outside `<temp>/jdbc_mcp_*` is ever touched.

---

## Configuration (env vars, all optional)

| What | Env var | Default |
|---|---|---|
| Query timeout (s) | `JDBC_MCP_QUERY_TIMEOUT` | 30 |
| Max rows returned | `JDBC_MCP_MAX_ROWS` | 1000 |
| `execute_java` timeout (s) | `JDBC_MCP_JAVA_TIMEOUT` | 30 |
| Max concurrent sessions | `JDBC_MCP_MAX_SESSIONS` | 50 |
| Session idle eviction (min) | `JDBC_MCP_SESSION_IDLE_MINUTES` | 30 |
| Force read-only sessions | `JDBC_MCP_READ_ONLY` | false |
| mitmproxy port | `JDBC_MCP_MITM_PORT` | 8889 |
| mitmproxy JSONL path | `JDBC_MCP_MITM_LOG_PATH` | `<temp>/jdbc_mcp_proxy.jsonl` |
| Non-proxyable driver list | `JDBC_MCP_NO_PROXY_DRIVERS` | see `Config.java` |
| File-driver list | `JDBC_MCP_FILE_DRIVERS` | excel,csv,json,xml,parquet,avro,orc |
| CData log verbosity (1–5) | `JDBC_MCP_LOG_VERBOSITY` | 5 |
| Log retention (days) | `JDBC_MCP_LOG_RETENTION_DAYS` | 3 |
| Log footprint cap (MB) | `JDBC_MCP_LOG_MAX_TOTAL_MB` | 512 |
| Startup rotation + sweep | `JDBC_MCP_LOG_SWEEP` | true |
| Max captured body (chars) | `JDBC_MCP_MITM_MAX_BODY` | 65536 |
| Redacted capture headers | `JDBC_MCP_MITM_REDACT_HEADERS` | authorization, cookie, x-api-key, … |

Each also has a `-Djdbc.mcp.*` system-property form — see
[Config.java](src/main/java/com/cdata/mcp/config/Config.java).

---

## Repo layout

```
.mcp.json                          Project-scope MCP registration (relative java -jar)
ONBOARDING.md                      Agent-driven setup runbook — hand this to Claude
CLAUDE.md                          Project instructions for Claude Code
.claude/skills/qa-ticket-verification/   The Jira QA skill (phases, strategies, pitfalls)
src/main/java/com/cdata/mcp/       Server source (tools, JDBC proxy tracing, mitm manager)
src/main/resources/proxy_addon.py  mitmproxy addon — bundled in the JAR, auto-extracted at runtime
mvnw.cmd / mvnw                    Self-provisioning Maven wrapper (Windows / macOS-Linux)
```

## Sharing this project

Share via git (a `.gitignore` is included), or zip it — **exclude** `target/`, `.mvn-dist/`,
`.idea/`, and `.claude/settings.local.json` (personal permissions; may reference local paths
and secrets):

```powershell
git init; git add .; git commit -m "JDBC Platform MCP server"    # .gitignore handles the exclusions
```

Recipients then follow the Quick start above.
