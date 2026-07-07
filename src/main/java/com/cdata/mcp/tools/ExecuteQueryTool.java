package com.cdata.mcp.tools;

import com.cdata.mcp.config.Config;
import com.cdata.mcp.jdbc.ConnectionSession;
import com.cdata.mcp.jdbc.ResultSetSerializer;
import com.cdata.mcp.jdbc.SessionManager;
import com.cdata.mcp.jdbc.TokenEstimator;
import com.cdata.mcp.jdbc.proxy.InterceptedCall;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.cdata.mcp.tools.JsonUtil.*;

public class ExecuteQueryTool {

    public static McpSchema.Tool tool() {
        return McpSchema.Tool.builder()
                .name("execute_query")
                .description("""
                        Execute a SQL SELECT query or stored procedure (EXEC) and return the result set.
                        Handles both plain SELECTs and CData EXEC calls (e.g. EXEC DownloadObjects …).
                        For EXEC: if the procedure returns a result set it is serialized normally; if it
                        returns only an update count (or void) update_count is returned instead.
                        Results are capped (max_rows) and the query is bounded by timeout_seconds.
                        If more rows exist than the cap, "truncated": true is returned.""")
                .inputSchema(schema(
                        Map.of(
                                "session_id",      strProp("Session ID from connect"),
                                "sql",             strProp("SQL SELECT or EXEC stored-procedure statement"),
                                "max_rows",        intProp("(Optional) Maximum rows to return. Defaults to server config."),
                                "timeout_seconds", intProp("(Optional) Query timeout in seconds. Defaults to server config.")
                        ),
                        List.of("session_id", "sql")
                ))
                .build();
    }

    public static McpSchema.CallToolResult handle(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments();
        String sessionId = (String) args.get("session_id");
        String sql = (String) args.get("sql");

        if (sessionId == null) return error("session_id is required");
        if (sql == null || sql.isBlank()) return error("sql is required");

        ConnectionSession session = SessionManager.get(sessionId);
        if (session == null) return error("Session not found: " + sessionId);

        Integer maxRowsArg = asInt(args.get("max_rows"));
        int maxRows = (maxRowsArg != null && maxRowsArg > 0) ? maxRowsArg : Config.defaultMaxRows();
        Integer timeoutArg = asInt(args.get("timeout_seconds"));
        int timeout = (timeoutArg != null && timeoutArg >= 0) ? timeoutArg : Config.queryTimeoutSeconds();

        boolean isExec = sql.trim().toUpperCase().startsWith("EXEC");

        session.beginCall();
        try (Statement st = session.getProxyConnection().createStatement()) {
            applyLimits(st, timeout, maxRows);

            if (!isExec) {
                // ── Plain SELECT path ──────────────────────────────────────────
                try (ResultSet rs = st.executeQuery(sql)) {
                    return buildResultSetResponse(rs, maxRows, session);
                }
            } else {
                // ── EXEC / stored-procedure path ───────────────────────────────
                // CData stored procs must use execute() rather than executeQuery();
                // they return a ResultSet via getResultSet() even though execute()
                // returns true (hasResultSet). Some procs return only an update count.
                boolean hasRs = st.execute(sql);
                if (hasRs) {
                    try (ResultSet rs = st.getResultSet()) {
                        return buildResultSetResponse(rs, maxRows, session);
                    }
                } else {
                    // Procedure returned no rows (e.g. a void/write-only proc).
                    int updateCount = st.getUpdateCount();
                    List<InterceptedCall> calls = session.endCall(0, 0);
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("update_count", updateCount);
                    response.put("intercepted_calls", calls.stream().map(InterceptedCall::toMap).collect(Collectors.toList()));
                    long tokens = TokenEstimator.estimate(toJson(response));
                    session.addEstimatedTokens(tokens);
                    response.put("_meta", Map.of("estimated_tokens", tokens,
                            "session_total_tokens", session.getTotalEstimatedTokens()));
                    return ok(response);
                }
            }
        } catch (Exception e) {
            return error("Query failed: " + e.getMessage());
        }
    }

    private static McpSchema.CallToolResult buildResultSetResponse(ResultSet rs, int maxRows,
                                                                    ConnectionSession session) throws Exception {
        ResultSetSerializer.SerializedResult result = ResultSetSerializer.serialize(rs, maxRows);
        session.setLastCallRowCount(result.rows().size());
        List<InterceptedCall> calls = session.endCall(result.rows().size(), 0);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("rows", result.rows());
        response.put("row_count", result.rows().size());
        response.put("truncated", result.truncated());
        response.put("columns", result.columns());
        response.put("intercepted_calls", calls.stream().map(InterceptedCall::toMap).collect(Collectors.toList()));

        long tokens = TokenEstimator.estimate(toJson(response));
        session.addEstimatedTokens(tokens);
        response.put("_meta", Map.of(
                "estimated_tokens", tokens,
                "session_total_tokens", session.getTotalEstimatedTokens(),
                "row_cap", maxRows));
        return ok(response);
    }

    /** Apply timeout and row cap to a statement; both are best-effort (some drivers ignore them). */
    static void applyLimits(Statement st, int timeout, int maxRows) {
        try { st.setQueryTimeout(timeout); } catch (Exception ignored) {}
        // +1 so the serializer can detect that more rows existed beyond the cap.
        try { if (maxRows > 0 && maxRows < Integer.MAX_VALUE) st.setMaxRows(maxRows + 1); } catch (Exception ignored) {}
    }
}
