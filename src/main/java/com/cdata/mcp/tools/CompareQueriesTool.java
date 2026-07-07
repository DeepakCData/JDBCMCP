package com.cdata.mcp.tools;

import com.cdata.mcp.config.Config;
import com.cdata.mcp.jdbc.ConnectionSession;
import com.cdata.mcp.jdbc.ResultSetSerializer;
import com.cdata.mcp.jdbc.SessionManager;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.cdata.mcp.tools.JsonUtil.*;

/**
 * Run two SELECTs and diff their result sets — the classic "expected vs actual" QA check.
 * Comparison is value-based and order-insensitive by default (rows treated as a multiset),
 * so it tolerates differing row order. Reports rows present only on one side and any count
 * mismatch. Records pass/fail into the session test report when a criterion is supplied.
 */
public class CompareQueriesTool {

    private static final int MAX_DIFF_ROWS = 50; // cap diff output to keep responses bounded

    public static McpSchema.Tool tool() {
        return McpSchema.Tool.builder()
                .name("compare_queries")
                .description("""
                        Run two SELECT queries and compare their result sets (expected vs actual).
                        Comparison is value-based and order-insensitive by default. Returns rows found only
                        in one side, row counts, and whether the column sets match. Useful for regression
                        checks (old vs new query), source-vs-target reconciliation, and acceptance criteria.
                        If criterion is given, the pass/fail outcome is recorded into the session test report.""")
                .inputSchema(schema(
                        Map.of(
                                "session_id",   strProp("Session ID from connect"),
                                "sql_actual",   strProp("The 'actual' SELECT (e.g. the new/under-test query)"),
                                "sql_expected", strProp("The 'expected' SELECT (e.g. the baseline/golden query)"),
                                "criterion",    strProp("(Optional) Label for this check; records it into the test report")
                        ),
                        List.of("session_id", "sql_actual", "sql_expected")
                ))
                .build();
    }

    public static McpSchema.CallToolResult handle(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments();
        String sessionId   = (String) args.get("session_id");
        String sqlActual   = (String) args.get("sql_actual");
        String sqlExpected = (String) args.get("sql_expected");
        String criterion   = asStr(args.get("criterion"));

        if (sessionId == null) return error("session_id is required");
        if (sqlActual == null || sqlActual.isBlank()) return error("sql_actual is required");
        if (sqlExpected == null || sqlExpected.isBlank()) return error("sql_expected is required");

        ConnectionSession session = SessionManager.get(sessionId);
        if (session == null) return error("Session not found: " + sessionId);

        try {
            ResultSetSerializer.SerializedResult actual = runQuery(session, sqlActual);
            ResultSetSerializer.SerializedResult expected = runQuery(session, sqlExpected);

            List<String> actualCols = columnNames(actual);
            List<String> expectedCols = columnNames(expected);
            boolean columnsMatch = actualCols.equals(expectedCols);

            // Multiset diff on row signatures.
            Map<String, Integer> actualBag = bag(actual.rows());
            Map<String, Integer> expectedBag = bag(expected.rows());

            List<String> onlyInActual = diff(actualBag, expectedBag);
            List<String> onlyInExpected = diff(expectedBag, actualBag);

            boolean passed = columnsMatch
                    && onlyInActual.isEmpty()
                    && onlyInExpected.isEmpty()
                    && actual.rows().size() == expected.rows().size();

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("passed", passed);
            response.put("columns_match", columnsMatch);
            if (!columnsMatch) {
                response.put("actual_columns", actualCols);
                response.put("expected_columns", expectedCols);
            }
            response.put("actual_row_count", actual.rows().size());
            response.put("expected_row_count", expected.rows().size());
            response.put("only_in_actual_count", onlyInActual.size());
            response.put("only_in_expected_count", onlyInExpected.size());
            response.put("only_in_actual", cap(onlyInActual));
            response.put("only_in_expected", cap(onlyInExpected));
            if (actual.truncated() || expected.truncated()) {
                response.put("warning", "One or both result sets were truncated by the row cap; comparison may be incomplete.");
            }
            if (criterion != null && !criterion.isBlank()) {
                response.put("criterion", criterion);
                String detail = passed ? "result sets match (" + actual.rows().size() + " rows)"
                        : "mismatch: only_in_actual=" + onlyInActual.size()
                          + ", only_in_expected=" + onlyInExpected.size()
                          + ", columns_match=" + columnsMatch;
                session.addCheck(AssertQueryTool.check(criterion, passed, detail, null));
            }
            return ok(response);
        } catch (Exception e) {
            if (criterion != null && !criterion.isBlank()) {
                session.addCheck(AssertQueryTool.check(criterion, false, "compare error: " + e.getMessage(), null));
            }
            return error("compare_queries failed: " + e.getMessage());
        }
    }

    private static ResultSetSerializer.SerializedResult runQuery(ConnectionSession session, String sql) throws Exception {
        int cap = Config.defaultMaxRows();
        session.beginCall();
        try (Statement st = session.getProxyConnection().createStatement()) {
            ExecuteQueryTool.applyLimits(st, Config.queryTimeoutSeconds(), cap);
            try (ResultSet rs = st.executeQuery(sql)) {
                ResultSetSerializer.SerializedResult r = ResultSetSerializer.serialize(rs, cap);
                session.setLastCallRowCount(r.rows().size());
                session.endCall(r.rows().size(), 0);
                return r;
            }
        }
    }

    private static List<String> columnNames(ResultSetSerializer.SerializedResult r) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> c : r.columns()) names.add(String.valueOf(c.get("name")));
        return names;
    }

    /** Build a multiset of row signatures (stable string form of the ordered cell values). */
    private static Map<String, Integer> bag(List<Map<String, Object>> rows) {
        Map<String, Integer> bag = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String sig = row.values().toString();
            bag.merge(sig, 1, Integer::sum);
        }
        return bag;
    }

    /** Signatures present in 'a' more often than in 'b' (one entry per surplus occurrence). */
    private static List<String> diff(Map<String, Integer> a, Map<String, Integer> b) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : a.entrySet()) {
            int surplus = e.getValue() - b.getOrDefault(e.getKey(), 0);
            for (int i = 0; i < surplus; i++) out.add(e.getKey());
        }
        return out;
    }

    private static List<String> cap(List<String> list) {
        return list.size() <= MAX_DIFF_ROWS ? list : new ArrayList<>(list.subList(0, MAX_DIFF_ROWS));
    }
}
