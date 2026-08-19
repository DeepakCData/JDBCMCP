# JDBC Platform MCP Server — Agent Onboarding & Setup Guide

> **This document is addressed to you, the Claude agent.** A user has handed you this file because
> they want the `jdbc-platform` MCP server working on their machine. Your job is to **drive the setup
> end-to-end**: detect what's already present, figure out what's missing, generate the exact
> commands for *their* OS and paths, ask permission, and only then make changes. Do not assume the
> environment matches the author's — verify everything.

---

## What this server is

A local MCP server (Java, stdio transport) that lets you open real JDBC connections to databases
and CData connectors (SAP ERP, Salesforce, SharePoint, ServiceNow, Snowflake, and 30+ more), run
SQL, inspect metadata, capture the driver's HTTP traffic via an auto-managed mitmproxy, and do
evidence-backed QA on Jira tickets (via the bundled `qa-ticket-verification` skill).

When fully set up, the user gets a `jdbc-platform` MCP server with 14 tools and the QA skill
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
   them exactly what's needed and where to get it. For the QA skill's Jira/ADO credentials, do this
   **up front** using the ready-made ask in the "First — collect the org credentials" section
   below (the org endpoints are fixed and pre-filled). When the user hands you a token, **you**
   store it in the safe git-ignored place — don't make them edit files.
6. **Report, then proceed in the fixed order.** After the preflight, show the user the status block
   (present / missing / needs action) before changing anything. **You still ask permission for every
   mutating command, but you do not ask the user which step to do next, and you do not reorder the
   sequence.** The order below encodes real dependencies; letting each run pick its own path is why
   different users end up with different setups. If the user asks to skip a step, do it, and record
   the skip verbatim in the final report.
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

## The setup sequence — fixed order, no exceptions

**Run these in exactly this order.** Do not reorder, do not run ahead, do not skip a step because
it "looks" done — confirm with the stated check. Each step lists what must be true to enter it and
what must be true to leave it. If you cannot satisfy an exit condition, say so and stop there
rather than moving on.

| # | Step | Enter only when | Leave only when |
|---|---|---|---|
| 1 | Read `.claude/skills/qa-ticket-verification/SKILL.md` end to end | — | You have read it. No commands have run yet. |
| 2 | Ask for the three org credentials (one message — see Step 2 below) | 1 done | The ask has been *sent*. **Do not wait for a reply** — continue to 3 while they fetch tokens. |
| 3 | Phase 0 preflight — all 12 checks | 2 sent | Every check has a result **and** the status block is printed |
| 4 | Phase 1 — build the JAR | Check 1 (Java 17+) passed | `target/jdbc-platform-1.0-SNAPSHOT.jar` exists |
| 5 | Phase 2 — mitmproxy | 4 done | `mitmdump --version` reports, **or** the user declined and you recorded it |
| 6 | Phase 3 — register the MCP server | JAR exists (4) | `claude mcp list` shows `jdbc-platform` |
| 7 | **HALT — restart Claude Code** | 6 done | The user confirms they restarted |
| 8 | Phase 5 — companion servers + credentials | Restarted (7); creds from 2 in hand | Each of Jira MCP / ADO MCP / Jira token is verified working, **or** declined and recorded |
| 9 | Phase 4 — copy the skill outside the repo | 8 done | Copied, **or** skipped (the default) |
| 10 | Phase 6 — verify end to end | Restarted (7) | `list_sessions` returned |
| 11 | Final report | 10 done | Printed using the §Final report template |

### Hard stops

These are not judgement calls:

- **Java 17+ missing (check 1) → STOP.** Nothing downstream can work. Tell the user what to install
  and end the run. Do not build, register, or continue to Phase 2.
- **The build fails (step 4) → STOP.** Surface the real error verbatim. Do not register a server
  whose JAR does not exist.
- **Never run step 10 before the restart in step 7.** A server registered in this session is not
  loaded in this session. Verifying before the restart produces a false failure and sends you
  debugging a working setup.
- **Never register before the JAR exists.** `claude mcp list` will show the server as failed and
  you will spend the next ten minutes on the wrong problem.

### Decisions that are already made

Previous runs diverged because these were left open. They are not open:

| Question | The answer — do not re-litigate |
|---|---|
| MCP scope? | **Project scope** — the shipped `.mcp.json`. Only offer user scope if the user says they want the server outside this repo. |
| Install mitmproxy? | **Offer it once.** If declined, record `capture: driver log only` and move on. Do not ask twice or treat it as blocking. |
| Copy the skill to the user's home? | **Skip by default.** The project-scoped skill already works inside this repo. Only copy if the user asks for it elsewhere. |
| Set up Jira / ADO / the Jira token? | **Recommend all three** to anyone doing ticket QA. If declined, record the limitation from Phase 5 and move on. |
| Which phases to run? | **All of them, in order.** Phases with nothing to do still get a line in the final report. |

---

## Step 2 — Collect the org credentials (ask immediately, then keep going)

This is step 2 of the sequence above: it runs *after* reading the skill and *before* the
preflight. Send the ask and move straight on to Phase 0 — the user fetches tokens while you
run the checks. Blocking here is wasted time.

Everyone who runs this server is inside the **same CData org**, so the endpoints are known ahead of
time — you only need three things from the user. **Ask for all three at the very start**, before
the preflight, and paste the exact links so they can generate the tokens in parallel while you run
Phase 0. Present it as a single message, roughly:

> To wire up the QA skill I need three things from you (we all share the same Jira + Azure DevOps
> org, so I've pre-filled everything else):
>
> 1. **Your CData Jira email** — the address you sign in to Jira with (e.g. `you@cdata.com`).
> 2. **A Jira API token** — create a *classic* token here:
>    https://id.atlassian.com/manage-profile/security/api-tokens → "Create API token", any name,
>    no scopes needed. (Used read-only, for finding the PR linked to a ticket.)
> 3. **An Azure DevOps PAT** — create one here:
>    https://dev.azure.com/cdatasoftware/_usersSettings/tokens → "New Token", scopes **Code (Read)**
>    and **Work Items (Read)**. (Used read-only, for reading the PR diff.)
>
> Paste all three back to me and I'll store them in the safe, git-ignored place myself — you won't
> need to edit any file.

Fixed org constants (do not ask the user for these — they're the same for everyone):

| Thing | Value |
|---|---|
| Jira site (`JIRA_BASE_URL`) | `https://cdatajira.atlassian.net` |
| Azure DevOps org | `cdatasoftware` |
| Jira token page | https://id.atlassian.com/manage-profile/security/api-tokens |
| ADO PAT page | https://dev.azure.com/cdatasoftware/_usersSettings/tokens |

**What you do with each once the user pastes them** (mechanics detailed in Phase 5):
- **Jira email + token** → write into `.claude/settings.local.json` `env` block as `JIRA_USER_EMAIL`
  and `JIRA_API_TOKEN` (plus `JIRA_BASE_URL` = the site above). You write the file; never make the
  user hand-edit JSON.
- **ADO PAT** → transformed (base64-encoded, see Phase 5) and used in the
  `claude mcp add azure-devops … PERSONAL_ACCESS_TOKEN=<encoded> … cdatasoftware …` registration
  command. Do not put the raw PAT straight into that command — see Phase 5 for why.

If the preflight (Phase 0) finds any of these already configured, say so and **don't re-ask for
that one** — only collect what's actually missing. A classic Jira API token can serve both the
dev-status PR lookup and (if needed) an API-token-based Jira MCP, so one token covers both.

---

## Phase 0 — Preflight detection (run these now)

> **Gate** — Enter: the credential ask (step 2) has been sent. Leave: all 12 checks have a recorded
> result and the status block below has been printed. Run **all** of them, including the ones you
> expect to pass; a skipped check is how a broken setup gets discovered three steps too late.

Run each check and record the result. Translate the command to the user's actual shell if needed.

| # | What to check | Command (Windows / PowerShell) | Pass condition |
|---|---|---|---|
| 1 | Java JDK 17+ | `java -version` | Reports version 17 or higher |
| 2 | The built server JAR | check `target/jdbc-platform-1.0-SNAPSHOT.jar` exists | File present |
| 3 | mitmproxy | `mitmdump --version` | Reports a version |
| 4 | Python (only if mitmproxy missing) | `python --version` | Present, for `pip install` |
| 5 | Current repo absolute path | `Get-Location` (or `pwd`) | Note it — needed for MCP registration |
| 6 | The QA skill in the repo | check `.claude/skills/qa-ticket-verification/SKILL.md` exists | File present |
| 7 | Is `jdbc-platform` already registered? | `claude mcp list` | Note if already present |
| 8 | Jira (Atlassian) MCP registered? | same `claude mcp list` output | An `atlassian` (or equivalent Jira) server, or a `claude.ai Atlassian` connector, present |
| 9 | Azure DevOps MCP registered? | same `claude mcp list` output | An `azure-devops` server present |
| 10 | Node.js / `npx` (only needed for check 9's fix) | `npx --version` | Reports a version — required by the Azure DevOps MCP package |
| 11 | Jira API token for PR discovery | check `JIRA_API_TOKEN` and `JIRA_USER_EMAIL` are set (e.g. `[bool]$env:JIRA_API_TOKEN`) | Both present |
| 12 | Azure DevOps PAT actually works | call the ADO MCP read tool `mcp__azure-devops__core_list_projects` | Returns projects — **not** an auth error / `! Needs authentication` |

Checks 8–9 are the **companion servers the QA skill depends on** — Jira to read the ticket,
Azure DevOps to read the implemented fix. Check 11 is the **Jira classic API token** that powers
definitive linked-PR discovery (the dev-status API — see Phase 5). Check 12 goes one step further
than "is it registered": it **exercises the ADO PAT** with a real read call, so an expired or
under-scoped token is caught now rather than mid-QA. (Only run check 12 if check 9 found the server
registered; skip it otherwise.) If any of these is missing, you will offer to set it up in Phase 5.
The JDBC server itself works without them, but **automated PR review needs both the Jira token (to
find the PR) and a working Azure DevOps PAT (to read it)** — without those, the QA skill can only
review a fix if the engineer hands the PR link or diff directly.

After running all of these, produce a short status table for the user, e.g.:

```
Java 17+         [ok] found (21.0.2)
Server JAR       [missing] not built yet
mitmproxy        [missing] not installed
QA skill         [ok] present in repo
MCP registered   [missing] not yet
Jira MCP         [missing] not registered — QA skill can't read tickets
Azure DevOps MCP [missing] not registered — QA skill can't read PR diffs
Azure DevOps PAT [n/a] MCP not registered (check once it is) — else [ok] valid / [fail] auth error
Node.js / npx    [ok] found (v20.11.0) — needed if registering Azure DevOps
Jira API token   [missing] not set — QA skill can't auto-find linked PRs
Repo path        C:\Users\<them>\...\jdbc-mcp-server
```

Then continue with **Phase 1 onward in order**. A phase with nothing to do is still entered,
confirmed, and given a line in the final report — that is what makes two runs comparable.
Do not ask the user which gap to address first.

---

## Phase 1 — Build the server JAR

> **Gate** — Enter: check 1 (Java 17+) passed. Leave: `target/jdbc-platform-1.0-SNAPSHOT.jar`
> exists. If the build fails, **STOP** and surface the error verbatim — do not continue to Phase 3.

The repo ships a bundled Maven wrapper — no separate Maven install needed. Build even if a JAR is
already present but the repo has newer commits than it, since a stale JAR silently runs old tool
behaviour; when in doubt, rebuild.

Command to propose (ask permission first):
```powershell
.\mvnw.cmd -q clean package
```
(macOS/Linux: `./mvnw -q clean package`)

Produces `target/jdbc-platform-1.0-SNAPSHOT.jar`. If the build fails, the usual cause is
`JAVA_HOME` not pointing at a JDK 17+. Surface the actual error to the user — don't guess.

---

## Phase 2 — Install mitmproxy

> **Gate** — Enter: Phase 1 produced the JAR. Leave: `mitmdump --version` reports a version, **or**
> the user declined and you recorded `capture: driver log only` for the final report. Offer once.

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

> **Gate** — Enter: the JAR exists. Leave: `claude mcp list` shows `jdbc-platform`. Then go straight
> to the restart halt (step 7) — **not** to Phase 6.

**Default: project scope.** Use the shipped `.mcp.json`. Only register at user scope if the user
says they want the server outside this repo — do not present it as an open question otherwise.

**This repo ships a project-scope `.mcp.json`** (launches `java -jar target/jdbc-platform-1.0-SNAPSHOT.jar`
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
claude mcp add jdbc-platform --scope user -- java -jar "<ABSOLUTE_PATH_TO_REPO>\target\jdbc-platform-1.0-SNAPSHOT.jar"
```

Substitute `<ABSOLUTE_PATH_TO_REPO>` with the path from Phase 0, check #5. On Windows use
backslashes; on macOS/Linux use forward slashes.

Tell the user to **restart Claude Code** afterward so the server loads.

---

## Phase 4 — Install the QA skill outside the repo

> **Gate** — Enter: Phase 5 is done. Leave: copied, or skipped. **Skipping is the default** — the
> project-scoped skill already works inside this repo. Only act if the user asks for it elsewhere.

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

> **Gate** — Enter: Claude Code has been restarted (step 7) and you hold whatever credentials the
> user sent in step 2. Leave: each of the three (Jira MCP, Azure DevOps MCP, Jira API token) is
> either **verified working** or **explicitly declined and recorded**. "Registered" is not the same
> as "working" — re-run preflight check 12 for ADO, and confirm the Jira token by running
> `find-linked-prs.ps1` against any real ticket key.

The QA skill needs, besides `jdbc-platform`: **Jira (Atlassian) MCP** to read tickets, **Azure
DevOps MCP** to read the implemented fix, and a **Jira classic API token** to *find* the linked PR.
**If the preflight (checks 8–9, 11) found any missing, proactively prompt the user here** — explain
what each enables, ask if they want it set up, and only run the command after a clear "yes". If the
user does QA tickets at all, recommend all three.

**Requirement for automated PR review:** finding *and* reviewing a fix needs **both** the Jira API
token (check 11 — to locate the linked PR) **and** the Azure DevOps MCP (checks 9–10 — to read the
diff). If either is absent, tell the user plainly that **PR review can only be done if they hand
the PR link or paste the diff directly** — the skill will not guess what changed. Note the
limitation and move on if they decline.

**Both commands below are verified working as of their last update. Do not improvise alternate
flags, package names, or URLs on a first attempt.** The Atlassian command is a stable hosted
service and hasn't changed. The Azure DevOps command depends on a third-party npm package that
**has already broken this exact command once** (see the version-pinning warning below) — if it
fails, diagnose per that subsection's steps before assuming PAT/scopes and before improvising a
different flag from memory.

### Jira (Atlassian) MCP server — lets the QA skill read tickets (Phase 1 of the skill)
If no Jira/Atlassian server or connector was found, register Atlassian's official remote MCP
server (HTTP transport, OAuth in the browser — no API token needed):
```powershell
claude mcp add --transport http atlassian https://mcp.atlassian.com/v1/mcp
```
After restarting Claude Code, the user completes sign-in via the `/mcp` command. If their
organization blocks the remote server, the alternative is an API-token-based Jira MCP server — the
site is fixed (`https://cdatajira.atlassian.net`) and you already collected the email + classic
token up front, so reuse those (the same classic token serves both this MCP and the dev-status PR
lookup). Never write a placeholder token — get the real one or skip.

### Azure DevOps MCP server — lets the QA skill read the fix/PR diff (Phase 2 of the skill)
If no `azure-devops` server was found, use the **PAT the user gave you up front** (see "First —
collect the org credentials"; generated at https://dev.azure.com/cdatasoftware/_usersSettings/tokens
with Code: Read and Work Items: Read scopes). The org is fixed — **`cdatasoftware`** — so don't ask
for it. Requires Node.js (`npx`) — check #10 in Phase 0.

**⚠️ `@azure-devops/mcp`'s auth contract has already changed once and will likely change again —
this whole subsection has broken silently before.** `npx -y` always resolves whatever is
*currently* published as `latest`, so a command that worked last month can fail today with no
change on your end. Known history: an older version read a raw PAT from `AZURE_DEVOPS_EXT_PAT`
via `-a pat`; **v2.9.0 removed that and instead reads `PERSONAL_ACCESS_TOKEN`, expecting it to
already be the full base64-encoded HTTP Basic credential** (`base64(":<PAT>")`, colon included) —
a raw PAT in that variable is silently rejected as a 401, not a validation error. **Pin the
version** so this doesn't drift again:

**⚠️ Shell matters — PowerShell mangles the `--` separator** and the command fails with
`error: unknown option '-y'`. Run this in **Git Bash or cmd**, not PowerShell.

1. Encode the PAT first — never put the raw PAT directly into the `claude mcp add` command:
   ```powershell
   [Convert]::ToBase64String([System.Text.Encoding]::ASCII.GetBytes(":$PAT"))
   ```
   (Bash: `echo -n ":$PAT" | base64`)
2. Register with the encoded value and a **pinned version**:
   ```bash
   claude mcp add azure-devops -e PERSONAL_ACCESS_TOKEN=<base64-encoded-value> -- npx -y @azure-devops/mcp@2.9.0 cdatasoftware
   ```
   `@2.9.0` is pinned because that's the version this exact command was last verified against —
   don't drop the version pin "to get the latest," that's what caused this break in the first
   place. If a future setup genuinely needs a newer version, re-verify the auth contract first
   (step below) and update the pin here, in one place, for everyone.
3. **If this exact command still fails**, don't guess at another flag or revert to an older
   pattern from memory — the contract may have changed again. Diagnose it directly:
   - Verify the PAT itself is valid, independent of the MCP server:
     `curl -s -o /dev/null -w "%{http_code}" -u ":<PAT>" "https://dev.azure.com/cdatasoftware/_apis/projects?api-version=7.1"`
     (expect `200`). If this fails, the PAT is the actual problem — regenerate it. If this
     succeeds, the PAT is fine and the problem is on the MCP package's side — don't regenerate it.
   - Run the server directly to see its real stderr (`claude mcp list` only shows `✔ Connected`/
     `✖ Failed`, not why): `npx -y @azure-devops/mcp@2.9.0 cdatasoftware` with the env var set,
     and read what it prints on failure — it usually names the exact env var it wants.
   - If needed, read the installed package's own source for the current contract: find it under
     `~/.npm/_npx/*/node_modules/@azure-devops/mcp/dist/` (or wherever `npx` cached it) and check
     `auth.js`/the README for what env var name and value format the installed version actually
     expects — don't trust this doc's env var name over what the code in front of you does.
   Never invent or hardcode a PAT.

**After either registration, verify before moving on** — don't just assume success:
```powershell
claude mcp list
```
Confirm the server shows `✔ Connected`, not `! Needs authentication` or an error. **For Azure
DevOps, go one step further and confirm the PAT actually works** (preflight check 12): call the ADO
read tool `mcp__azure-devops__core_list_projects` and confirm it returns projects rather than an
auth error. `✔ Connected` only means the process launched — a bad/expired PAT still lists as
connected but fails on the first real call, so this active ping is what actually proves the PAT.
If the user skips a server entirely, tell them the QA skill will ask them to paste ticket details /
PR links manually (or proceed without fix review) when it reaches that phase.

**Troubleshooting (don't reach for a different command — fix the actual cause):**
- First run after registering Azure DevOps can take 10–20s while `npx` downloads
  `@azure-devops/mcp` — re-run `claude mcp list` after a short wait before assuming failure.
- **`! Needs authentication`, a 401 on the first real tool call, or `✔ Connected` that still fails
  when you actually call a tool (e.g. `core_list_projects`) — do NOT assume the PAT is bad and
  regenerate it first.** Verify the PAT independently before touching it:
  `curl -s -o /dev/null -w "%{http_code}" -u ":<PAT>" "https://dev.azure.com/cdatasoftware/_apis/projects?api-version=7.1"`
  (expect `200`). If that returns `200`, the PAT is fine — the problem is almost certainly the
  package's env-var name/encoding contract (see the version-pinning warning above), not the
  credential. Only regenerate the PAT if this curl call itself fails.
- `ChainedTokenCredential authentication failed` / `EnvironmentCredential is unavailable` — an
  older package version read `-a env` as the auth mode, which ignores your PAT entirely and tries
  Azure's identity chain instead. Re-register using the current pinned command above
  (`PERSONAL_ACCESS_TOKEN`, no `-a` flag).
- `error: unknown option '-y'` during registration — you ran the command in PowerShell. Use
  Git Bash or cmd instead.
- `npx --version` missing — Node.js isn't installed; point the user to https://nodejs.org, don't
  try to install it silently.
- **Secrets left behind after registration:** the base64-encoded credential passes through your
  shell command line, so it lands in shell history (`~/.bash_history` or PowerShell's
  `Get-History`) and in `~/.claude.json` (plaintext, wherever the server was registered). Treat
  the encoded value as equally sensitive as the raw PAT — base64 is trivially reversible and is
  directly usable for Basic auth as-is. This exposure is unavoidable with `claude mcp add`'s
  design; mention it to the user so they can clear history if they're on a shared machine, and
  tell them the PAT should be revoked/rotated if it's ever pasted somewhere they don't fully
  trust (including this chat).

### Jira API token — enables definitive linked-PR discovery (Phase 2 of the skill)
The skill's Phase 2 finds the PR(s) linked to a ticket by calling Jira's dev-status API (the same
data the Jira "Development" panel shows in the browser). This is far more reliable than scraping
comments or guessing branch names — it even catches back-port PRs on other release branches. It is
driven by the bundled helper `.claude/skills/qa-ticket-verification/find-linked-prs.ps1`.

This needs a **classic Atlassian API token** used with HTTP Basic auth. **OAuth 2.0 / scoped
tokens do NOT work** with the dev-status endpoint — don't create those.

1. Use the **token + email the user gave you up front** (see "First — collect the org
   credentials"). If you somehow reached here without them, ask now: generate a *classic* token at
   https://id.atlassian.com/manage-profile/security/api-tokens (any name, no scopes) and paste it
   with the Atlassian email. Tell them plainly: *"Paste me the token and I'll put it in the safe,
   git-ignored place myself — you don't need to edit any file."* This token is for **read-only
   use** (dev-status lookups); it must never be used for Jira writes.
2. **You write it** — do not make the user edit JSON by hand. Put the token and email into the
   repo's **`.claude/settings.local.json`** under an `env` block. That file is the safe, expected
   home for it: it is git-ignored, so the secret is never committed. The block looks like:
   ```json
   {
     "env": {
       "JIRA_API_TOKEN": "<the-classic-token-they-pasted>",
       "JIRA_USER_EMAIL": "<their-atlassian-email>",
       "JIRA_BASE_URL": "https://cdatajira.atlassian.net"
     }
   }
   ```
   Merge into any existing `env`/top-level keys — don't clobber the file. `JIRA_BASE_URL` is
   optional (defaults to `https://cdatajira.atlassian.net`); set it for a different Jira site.
   Never write a placeholder token — get the real one from the user or skip this step. After
   writing, confirm to the user where you put it and that it won't be committed.
3. Verify end-to-end against a ticket you know has a PR:
   ```powershell
   pwsh .claude/skills/qa-ticket-verification/find-linked-prs.ps1 <TICKET-KEY>
   ```
   It should print the linked PR(s) with id, status, branch, and URL. The script auto-discovers the
   SCM app, so it works for other Jira/ADO setups too. If it errors that the env vars aren't set,
   the settings weren't picked up — restart Claude Code so the new `env` block loads.

### CData driver JARs (required to connect to CData sources — cannot be automated)
The server includes **no** driver JARs — they are licensed separately. Ask the user:
- Which connectors do they need (Salesforce, SAP ERP, SharePoint, etc.)?
- Where are the `.jar` files on their machine? (typical Windows path:
  `C:\Program Files\CData\CData JDBC Driver for <Product> 2025\lib\`)
- If they don't have them, point them to https://www.cdata.com/jdbc/ to license/download.

You'll pass the JAR path to `load_driver` at runtime — you don't install it.

---

## Phase 6 — Verify it works

> **Gate** — Enter: Claude Code has been restarted since registration. Leave: `list_sessions`
> returned successfully. **Do not run this before the restart** — the server registered in the
> previous session is not loaded in it, and a failure here before a restart means nothing.

Once the MCP server is registered and Claude Code restarted, confirm the connection end-to-end:

1. Confirm the server is live — call the `list_sessions` tool. An empty session list means the
   server started correctly.
2. If the user provided a driver JAR and connection string, do a minimal `load_driver` ->
   `connect` -> `list_sessions` round-trip and report the result. **Note:** on `connect` you may
   see a message that proxy settings were overridden to 8889 — that is expected, not an error (see
   "Proxy behavior" below). Pass the connection string exactly as given and do not try to bypass
   the override.

### Final report

**Print exactly these lines, in this order, every time.** Every row gets a value — `[ok]`,
`[missing]`, `[declined]`, or `[skipped]` with the reason. Two users who ran the same setup should
see the same shape, which is the point:

```
SETUP COMPLETE — jdbc-platform

Java                 [ok] 21.0.2
Server JAR           [ok] built at <path>
mitmproxy            [ok] 11.0.0            | or [declined] capture: driver log only
MCP registration     [ok] project scope via .mcp.json
Restart confirmed    [ok]
Server responding    [ok] list_sessions returned 0 sessions
QA skill             [ok] project scope     | or [ok] also copied to ~/.claude/skills
Jira MCP             [ok] verified          | or [declined] cannot read tickets
Azure DevOps MCP     [ok] verified (projects listed)  | or [declined] cannot read PR diffs
Jira API token       [ok] verified via find-linked-prs.ps1  | or [missing] no auto PR discovery
Driver JARs          [pending] user has not provided any yet

Not done, and why:
  - <verbatim list of every skip / decline / failure, or "nothing">

You can now ask me to: <the next actions actually available given the above>
```

Do not summarise in prose instead. Do not omit rows that are `[ok]`. If something failed, it
appears under "Not done, and why" with the real error — never silently dropped.

---

# Reference (for the agent during operation)

## The 14 tools

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
- **QA skill can't find the linked PR** — run `find-linked-prs.ps1 <TICKET-KEY>` directly to see
  the failure. Common causes: `JIRA_API_TOKEN`/`JIRA_USER_EMAIL` not set or not loaded (restart
  Claude Code after editing `.claude/settings.local.json`); an OAuth/scoped token was used instead
  of a classic API token (the dev-status endpoint rejects those); or the ticket genuinely has no
  linked PR yet. To then *read* a found PR you still need the Azure DevOps MCP — if its PAT is
  missing/expired, `claude mcp list` shows `! Needs authentication`.
