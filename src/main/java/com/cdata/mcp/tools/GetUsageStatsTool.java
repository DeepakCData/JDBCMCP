package com.cdata.mcp.tools;

import com.cdata.mcp.jdbc.ConnectionSession;
import com.cdata.mcp.jdbc.SessionManager;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.cdata.mcp.tools.JsonUtil.*;

public class GetUsageStatsTool {

    public static McpSchema.Tool tool() {
        return McpSchema.Tool.builder()
                .name("get_usage_stats")
                .description("Return cumulative usage statistics for a session: queries run, rows returned, intercepted calls, duration, estimated tokens.")
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

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("session_id", sessionId);
        stats.put("total_queries", session.getTotalQueriesRun());
        stats.put("total_rows_returned", session.getTotalRowsReturned());
        stats.put("total_intercepted_calls", session.getTotalInterceptedCalls());
        stats.put("total_duration_ms", session.getTotalDurationMs());
        stats.put("estimated_tokens_used", session.getTotalEstimatedTokens());
        return ok(stats);
    }
}
