# JDBC Platform MCP Server — Claude Instructions

**New machine / server not yet running?** Follow ONBOARDING.md — it is the agent-driven setup
runbook (preflight checks, build, mitmproxy, MCP registration). README.md has the human-facing
overview plus the proxy and log-capture rules.

**Follow ONBOARDING.md's numbered sequence exactly, top to bottom.** It opens with a fixed
step order, entry/exit gates per phase, hard stops, and a required final-report format. Ask
permission for each mutating command, but do not choose the order, skip ahead, or let the user
pick which step comes next — improvised ordering is why setups drifted between machines. Two
gates matter most: never register the MCP server before the JAR is built, and never verify
before Claude Code has been restarted.

For JDBC driver testing and Jira ticket QA, use the **qa-ticket-verification** skill — it contains
the full operational guide (phases, tool workflow, proxy/capture handling, test strategies,
pitfalls, and CData internals). Invoke it whenever a QA engineer asks to test, verify, validate,
or reproduce a Jira ticket or its fix.

The skill also bundles **operation-level checklists** under
`.claude/skills/qa-ticket-verification/checklists/` (SELECT, INSERT, UPDATE, DELETE, batch
operations, stored procedures). When someone asks for vague operation testing — "run select tests
on this table", "test inserts", "check batch ops" — execute the matching checklist instead of
guessing coverage.
