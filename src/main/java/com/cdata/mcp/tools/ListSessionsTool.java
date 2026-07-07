package com.cdata.mcp.tools;

import com.cdata.mcp.config.Config;
import com.cdata.mcp.jdbc.SessionManager;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.cdata.mcp.tools.JsonUtil.*;

public class ListSessionsTool {

    public static McpSchema.Tool tool() {
        return McpSchema.Tool.builder()
                .name("list_sessions")
                .description("""
                        List all currently open JDBC sessions with their idle time, age, read-only flag,
                        and per-session activity. Idle sessions are auto-closed by the server after the
                        configured idle timeout; use this to find and disconnect orphaned sessions.""")
                .inputSchema(schema(Map.of(), List.of()))
                .build();
    }

    public static McpSchema.CallToolResult handle(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sessions", SessionManager.list());
        response.put("count", SessionManager.size());
        response.put("max_sessions", Config.maxSessions());
        response.put("idle_timeout_minutes", Config.sessionIdleMinutes());
        return ok(response);
    }
}
