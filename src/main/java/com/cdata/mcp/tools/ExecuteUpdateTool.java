package com.cdata.mcp.tools;

import com.cdata.mcp.jdbc.ConnectionSession;
import com.cdata.mcp.jdbc.QueryBudget;
import com.cdata.mcp.jdbc.SessionManager;
import com.cdata.mcp.jdbc.TokenEstimator;
import com.cdata.mcp.jdbc.proxy.InterceptedCall;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.cdata.mcp.tools.JsonUtil.*;

public class ExecuteUpdateTool {

    public static McpSchema.Tool tool() {
        return McpSchema.Tool.builder()
                .name("execute_update")
                .description("""
                        Execute a SQL INSERT, UPDATE, DELETE, or DDL statement. Returns the update count.
                        Rejected if the session was opened read_only. Bounded by timeout_seconds.

                        Reading the driver's HTTP traffic: _meta.capture_from/capture_to are byte offsets into
                        mitm_log_path (returned by connect) bounding THIS call's requests. Read that byte range
                        rather than searching the capture by timestamp or reading it from the top — it is
                        append-only and shared by every session, and its ts field is UTC.
                        capture_entries: 0 means the driver answered locally, with no backend request.""")
                .inputSchema(schema(
                        Map.of(
                                "session_id",      strProp("Session ID from connect"),
                                "sql",             strProp("SQL DML or DDL statement to execute"),
                                "timeout_seconds", intProp("(Optional) Statement timeout in seconds. Defaults to server config.")
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
        if (session.isReadOnly()) {
            return error("Session is read-only; execute_update is disabled. Reconnect with read_only=false to allow writes.");
        }

        int timeout = ExecuteQueryTool.resolveTimeout(args);

        session.beginCall();
        try (Statement st = session.getProxyConnection().createStatement();
             QueryBudget.Disarm disarm = ExecuteQueryTool.applyLimits(session, st, timeout, 0)) {
            int count = st.executeUpdate(sql);
            List<InterceptedCall> calls = session.endCall(0, 0);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("update_count", count);
            response.put("intercepted_calls", calls.stream().map(InterceptedCall::toMap).collect(Collectors.toList()));

            long tokens = TokenEstimator.estimate(toJson(response));
            session.addEstimatedTokens(tokens);
            response.put("_meta", meta(session, tokens, null));
            return ok(response);
        } catch (Exception e) {
            return errorWithTrace("Update failed: " + describe(e), session);
        }
    }
}
