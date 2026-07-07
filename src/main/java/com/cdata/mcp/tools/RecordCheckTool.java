package com.cdata.mcp.tools;

import com.cdata.mcp.jdbc.ConnectionSession;
import com.cdata.mcp.jdbc.SessionManager;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;

import static com.cdata.mcp.tools.JsonUtil.*;

/**
 * Manually record a pass/fail check into the session test report.
 *
 * Use this when the verification can't be expressed as a SELECT — e.g.:
 *   • A stored-procedure result (Status, FailureReason, LocalFile from DownloadObjects)
 *   • A filesystem outcome ("file exists and is 320 bytes")
 *   • An execute_java result that was evaluated in code
 *   • Any assertion you've already evaluated outside the DB query tools
 *
 * The recorded check shows up in get_test_report alongside assert_query and
 * compare_queries results, giving a complete picture of the QA session.
 */
public class RecordCheckTool {

    public static McpSchema.Tool tool() {
        return McpSchema.Tool.builder()
                .name("record_check")
                .description("""
                        Record a manual pass/fail check into the session test report.
                        Use when the verification cannot be expressed as a SELECT — e.g.:
                          • Stored-procedure outputs (Status, FailureReason, LocalFile from DownloadObjects)
                          • Filesystem / side-effect outcomes ("file written", "folder created")
                          • execute_java results already evaluated in code
                          • Any assertion you've evaluated outside the query tools
                        The check appears in get_test_report alongside assert_query / compare_queries results.""")
                .inputSchema(schema(
                        Map.of(
                                "session_id", strProp("Session ID from connect"),
                                "criterion",  strProp("Label for this check, e.g. 'TC1: CreateFolders=true creates missing dirs'"),
                                "passed",     boolProp("true = check passed, false = check failed"),
                                "detail",     strProp("(Optional) What was observed — actual value, error message, filename, etc."),
                                "sql",        strProp("(Optional) The SQL / EXEC statement that produced the result, for the report evidence trail.")
                        ),
                        List.of("session_id", "criterion", "passed")
                ))
                .build();
    }

    public static McpSchema.CallToolResult handle(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments();
        String sessionId = (String) args.get("session_id");
        String criterion = asStr(args.get("criterion"));
        Object passedRaw = args.get("passed");
        String detail    = asStr(args.get("detail"));
        String sql       = asStr(args.get("sql"));

        if (sessionId == null) return error("session_id is required");
        if (criterion == null || criterion.isBlank()) return error("criterion is required");
        if (passedRaw == null) return error("passed is required (true or false)");

        ConnectionSession session = SessionManager.get(sessionId);
        if (session == null) return error("Session not found: " + sessionId);

        boolean passed = asBool(passedRaw, false);
        session.addCheck(AssertQueryTool.check(criterion, passed,
                detail != null ? detail : (passed ? "manually recorded as passed" : "manually recorded as failed"),
                sql));

        return ok(Map.of(
                "recorded", true,
                "criterion", criterion,
                "passed", passed
        ));
    }
}
