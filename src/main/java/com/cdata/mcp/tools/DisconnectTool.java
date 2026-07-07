package com.cdata.mcp.tools;

import com.cdata.mcp.jdbc.ConnectionSession;
import com.cdata.mcp.jdbc.SessionManager;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;

import static com.cdata.mcp.tools.JsonUtil.*;

public class DisconnectTool {

    public static McpSchema.Tool tool() {
        return McpSchema.Tool.builder()
                .name("disconnect")
                .description("Close the JDBC connection and remove the session.")
                .inputSchema(schema(
                        Map.of("session_id", strProp("Session ID to close")),
                        List.of("session_id")
                ))
                .build();
    }

    public static McpSchema.CallToolResult handle(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        String sessionId = (String) request.arguments().get("session_id");
        if (sessionId == null) return error("session_id is required");

        ConnectionSession session = SessionManager.remove(sessionId);
        if (session == null) return error("Session not found: " + sessionId);

        try {
            session.getProxyConnection().close();
            return ok(Map.of("status", "closed", "session_id", sessionId));
        } catch (Exception e) {
            return error("Close failed: " + e.getMessage());
        }
    }
}
