package com.cdata.mcp.tools;

import com.cdata.mcp.jdbc.ConnectionSession;
import com.cdata.mcp.jdbc.SessionManager;
import com.cdata.mcp.log.LogJanitor;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.cdata.mcp.tools.JsonUtil.*;

public class DisconnectTool {

    public static McpSchema.Tool tool() {
        return McpSchema.Tool.builder()
                .name("disconnect")
                .description("""
                        Close the JDBC connection and remove the session.

                        The session's CData driver log (logfile_path) is deleted, since a Verbosity=5 log can
                        reach hundreds of MB. Extract whatever you need from it BEFORE disconnecting, or pass
                        keep_logfile=true when the log itself is evidence for a ticket.""")
                .inputSchema(schema(
                        Map.of("session_id",    strProp("Session ID to close"),
                               "keep_logfile",  boolProp("(Optional) Keep this session's driver log on disk instead of deleting it. Default false.")),
                        List.of("session_id")
                ))
                .build();
    }

    public static McpSchema.CallToolResult handle(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        String sessionId = (String) request.arguments().get("session_id");
        if (sessionId == null) return error("session_id is required");

        boolean keepLogfile = Boolean.TRUE.equals(request.arguments().get("keep_logfile"));

        ConnectionSession session = SessionManager.remove(sessionId);
        if (session == null) return error("Session not found: " + sessionId);

        // Give back the process-global proxy properties before anything else can fail.
        if (session.isJvmProxyApplied()) ConnectTool.releaseJvmProxy();

        try {
            session.getProxyConnection().close();
        } catch (Exception e) {
            // The session is already out of the manager, so it will never reach disconnect again —
            // reclaim now (best effort) rather than leaving the log for the next startup sweep.
            if (!keepLogfile) LogJanitor.deleteSessionLog(session.getLogfilePath());
            return error("Close failed: " + describe(e));
        }

        // Reclaim after the close: the driver may still be flushing to the log while the
        // connection is open, and on Windows an open handle would defeat the delete.
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "closed");
        response.put("session_id", sessionId);
        if (!session.getLogfilePath().isEmpty()) {
            if (keepLogfile) {
                response.put("logfile_kept", session.getLogfilePath());
            } else {
                long freed = LogJanitor.deleteSessionLog(session.getLogfilePath());
                response.put("logfile_deleted", freed > 0);
                response.put("logfile_bytes_freed", freed);
            }
        }
        return ok(response);
    }
}
