package com.cdata.mcp.tools;

import com.cdata.mcp.config.Config;
import com.cdata.mcp.jdbc.ConnectionSession;
import com.cdata.mcp.jdbc.ResultSetSerializer;
import com.cdata.mcp.jdbc.SessionManager;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.cdata.mcp.tools.JsonUtil.*;

/**
 * Run a SELECT and assert a condition against it — the building block of a QA check.
 * Supports three assertion modes (checked in this order):
 *   1. expected_row_count  — compare the number of returned rows
 *   2. expected_value      — compare the first column of the first row
 *   3. neither             — pass if at least one row is returned (existence check)
 * When a criterion label is supplied, the pass/fail outcome is recorded into the
 * session's test report (see get_test_report).
 */
public class AssertQueryTool {

    public static McpSchema.Tool tool() {
        return McpSchema.Tool.builder()
                .name("assert_query")
                .description("""
                        Run a SELECT and assert an expected outcome — for verifying a Jira ticket's acceptance criteria.
                        Provide ONE of:
                          • expected_row_count — assert the row count (use comparator for >=, <=, etc.)
                          • expected_value     — assert the first column of the first row equals/compares to a value
                          • neither            — passes if the query returns at least one row
                        comparator: eq (default), ne, gt, gte, lt, lte.
                        If criterion is given, the result is recorded into the session test report.""")
                .inputSchema(schema(
                        Map.of(
                                "session_id",         strProp("Session ID from connect"),
                                "sql",                strProp("SQL SELECT statement to evaluate"),
                                "expected_row_count", intProp("(Optional) Expected number of rows"),
                                "expected_value",     anyProp("(Optional) Expected value of the first column of the first row"),
                                "comparator",         strProp("(Optional) eq (default), ne, gt, gte, lt, lte"),
                                "criterion",          strProp("(Optional) Label for this check; records it into the test report")
                        ),
                        List.of("session_id", "sql")
                ))
                .build();
    }

    public static McpSchema.CallToolResult handle(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments();
        String sessionId = (String) args.get("session_id");
        String sql       = (String) args.get("sql");

        if (sessionId == null) return error("session_id is required");
        if (sql == null || sql.isBlank()) return error("sql is required");

        ConnectionSession session = SessionManager.get(sessionId);
        if (session == null) return error("Session not found: " + sessionId);

        Integer expectedRowCount = asInt(args.get("expected_row_count"));
        boolean hasRowCount = expectedRowCount != null;
        Object expectedValue = args.get("expected_value");
        boolean hasValue = expectedValue != null;
        String comparator = asStr(args.get("comparator"));
        String criterion = asStr(args.get("criterion"));

        // Modest cap — assertions look at counts or a single value, not bulk data.
        int cap = hasRowCount ? Config.defaultMaxRows() : 1;

        session.beginCall();
        try (Statement st = session.getProxyConnection().createStatement()) {
            ExecuteQueryTool.applyLimits(st, Config.queryTimeoutSeconds(), cap);
            try (ResultSet rs = st.executeQuery(sql)) {
                ResultSetSerializer.SerializedResult result = ResultSetSerializer.serialize(rs, cap);
                session.setLastCallRowCount(result.rows().size());
                session.endCall(result.rows().size(), 0);

                Map<String, Object> response = new LinkedHashMap<>();
                boolean passed;
                String mode;
                Object actual;
                Object expected;
                String usedComparator = (comparator == null || comparator.isBlank()) ? "eq" : comparator;

                if (hasRowCount) {
                    mode = "row_count";
                    actual = result.rows().size();
                    expected = expectedRowCount;
                    if (result.truncated()) {
                        response.put("warning", "Row count assertion may be unreliable: results were truncated at " + cap + " rows.");
                    }
                    passed = Compare.evaluate(actual, expected, usedComparator);
                } else if (hasValue) {
                    mode = "value";
                    actual = firstCell(result);
                    expected = expectedValue;
                    passed = Compare.evaluate(actual, expected, usedComparator);
                } else {
                    mode = "exists";
                    actual = result.rows().size();
                    expected = ">= 1 row";
                    usedComparator = "exists";
                    passed = !result.rows().isEmpty();
                }

                response.put("passed", passed);
                response.put("mode", mode);
                response.put("comparator", usedComparator);
                response.put("actual", actual);
                response.put("expected", expected);
                response.put("row_count", result.rows().size());
                if (criterion != null && !criterion.isBlank()) {
                    response.put("criterion", criterion);
                    session.addCheck(check(criterion, passed,
                            "assert_query[" + mode + "] actual=" + actual + " " + usedComparator + " expected=" + expected, sql));
                }
                return ok(response);
            }
        } catch (Exception e) {
            // A failed query is itself a check failure when a criterion is being tracked.
            if (criterion != null && !criterion.isBlank()) {
                session.addCheck(check(criterion, false, "query error: " + e.getMessage(), sql));
            }
            return error("assert_query failed: " + e.getMessage());
        }
    }

    private static Object firstCell(ResultSetSerializer.SerializedResult result) {
        if (result.rows().isEmpty() || result.columns().isEmpty()) return null;
        String firstCol = (String) result.columns().get(0).get("name");
        return result.rows().get(0).get(firstCol);
    }

    static Map<String, Object> check(String criterion, boolean passed, String detail, String sql) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("criterion", criterion);
        m.put("passed", passed);
        m.put("detail", detail);
        if (sql != null) m.put("sql", sql);
        m.put("at", java.time.Instant.now().toString());
        return m;
    }
}
