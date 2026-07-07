package com.cdata.mcp.tools;

import com.cdata.mcp.jdbc.ConnectionSession;
import com.cdata.mcp.jdbc.SessionManager;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.cdata.mcp.tools.JsonUtil.*;

/**
 * Render the accumulated assertion/comparison checks for a session as a Markdown
 * test report — the artifact a QA engineer pastes back into a Jira ticket.
 */
public class GetTestReportTool {

    public static McpSchema.Tool tool() {
        return McpSchema.Tool.builder()
                .name("get_test_report")
                .description("""
                        Return a Markdown test report summarizing every recorded check for the session
                        (those produced by assert_query / compare_queries with a criterion), plus pass/fail
                        totals and session activity stats. Use this to produce evidence for a Jira ticket.""")
                .inputSchema(schema(
                        Map.of("session_id", strProp("Session ID from connect")),
                        List.of("session_id")
                ))
                .build();
    }

    public static McpSchema.CallToolResult handle(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        String sessionId = (String) request.arguments().get("session_id");
        if (sessionId == null) return error("session_id is required");

        ConnectionSession session = SessionManager.get(sessionId);
        if (session == null) return error("Session not found: " + sessionId);

        List<Map<String, Object>> checks = session.getChecks();
        int total = checks.size();
        int passed = 0;
        for (Map<String, Object> c : checks) if (Boolean.TRUE.equals(c.get("passed"))) passed++;
        int failed = total - passed;

        StringBuilder md = new StringBuilder();
        md.append("# QA Test Report\n\n");
        md.append("**Session:** ").append(sessionId).append("  \n");
        md.append("**Result:** ").append(failed == 0 && total > 0 ? "✅ PASS" : (total == 0 ? "⚠️ NO CHECKS" : "❌ FAIL"))
          .append("  \n");
        md.append("**Checks:** ").append(passed).append(" passed, ").append(failed).append(" failed, ")
          .append(total).append(" total\n\n");

        if (total > 0) {
            md.append("| # | Criterion | Status | Detail |\n");
            md.append("|---|-----------|--------|--------|\n");
            int i = 1;
            for (Map<String, Object> c : checks) {
                boolean ok = Boolean.TRUE.equals(c.get("passed"));
                md.append("| ").append(i++).append(" | ")
                  .append(mdCell(c.get("criterion"))).append(" | ")
                  .append(ok ? "✅ pass" : "❌ fail").append(" | ")
                  .append(mdCell(c.get("detail"))).append(" |\n");
            }
            md.append("\n");
        }

        md.append("## Session activity\n\n");
        md.append("- Queries run: ").append(session.getTotalQueriesRun()).append("\n");
        md.append("- Rows returned: ").append(session.getTotalRowsReturned()).append("\n");
        md.append("- Intercepted JDBC calls: ").append(session.getTotalInterceptedCalls()).append("\n");
        md.append("- Total JDBC duration (ms): ").append(session.getTotalDurationMs()).append("\n");
        md.append("- Estimated tokens used: ").append(session.getTotalEstimatedTokens()).append("\n");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("report_markdown", md.toString());
        response.put("total_checks", total);
        response.put("passed", passed);
        response.put("failed", failed);
        response.put("overall_passed", total > 0 && failed == 0);
        response.put("checks", checks);
        return ok(response);
    }

    /** Escape pipe and newline so a value stays inside one Markdown table cell. */
    private static String mdCell(Object o) {
        if (o == null) return "";
        return o.toString().replace("|", "\\|").replace("\n", " ");
    }
}
