package com.cdata.mcp.tools;

import com.cdata.mcp.config.Config;
import com.cdata.mcp.jdbc.ConnectionSession;
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
                        Rejected if the session was opened read_only. Bounded by timeout_seconds.""")
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

        Integer timeoutArg = asInt(args.get("timeout_seconds"));
        int timeout = (timeoutArg != null && timeoutArg >= 0) ? timeoutArg : Config.queryTimeoutSeconds();

        session.beginCall();
        try (Statement st = session.getProxyConnection().createStatement()) {
            try { st.setQueryTimeout(timeout); } catch (Exception ignored) {}
            int count = st.executeUpdate(sql);
            List<InterceptedCall> calls = session.endCall(0, 0);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("update_count", count);
            response.put("intercepted_calls", calls.stream().map(InterceptedCall::toMap).collect(Collectors.toList()));

            long tokens = TokenEstimator.estimate(toJson(response));
            session.addEstimatedTokens(tokens);
            response.put("_meta", Map.of(
                    "estimated_tokens", tokens,
                    "session_total_tokens", session.getTotalEstimatedTokens()));
            return ok(response);
        } catch (Exception e) {
            return error("Update failed: " + e.getMessage());
        }
    }
}
