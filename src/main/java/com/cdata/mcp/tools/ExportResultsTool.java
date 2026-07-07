package com.cdata.mcp.tools;

import com.cdata.mcp.config.Config;
import com.cdata.mcp.jdbc.ConnectionSession;
import com.cdata.mcp.jdbc.ResultSetSerializer;
import com.cdata.mcp.jdbc.SessionManager;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.cdata.mcp.tools.JsonUtil.*;

/**
 * Run a SELECT and write the results to a CSV file on disk — for attaching evidence
 * (sample data, reconciliation output) to a Jira ticket. Honors the row cap and
 * reports whether the export was truncated.
 */
public class ExportResultsTool {

    public static McpSchema.Tool tool() {
        return McpSchema.Tool.builder()
                .name("export_results")
                .description("""
                        Execute a SELECT and write the result set to a CSV file (RFC4180-quoted, UTF-8).
                        Returns the absolute path, row count, and whether the data was truncated by the row cap.
                        Binary cells are written as [base64:<bytes>]; truncated text cells are marked. Use a
                        larger max_rows for full exports.""")
                .inputSchema(schema(
                        Map.of(
                                "session_id", strProp("Session ID from connect"),
                                "sql",        strProp("SQL SELECT statement to export"),
                                "file_path",  strProp("Absolute path of the CSV file to write, e.g. C:\\\\temp\\\\out.csv"),
                                "max_rows",   intProp("(Optional) Maximum rows to export. Defaults to server config.")
                        ),
                        List.of("session_id", "sql", "file_path")
                ))
                .build();
    }

    public static McpSchema.CallToolResult handle(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments();
        String sessionId = (String) args.get("session_id");
        String sql       = (String) args.get("sql");
        String filePath  = (String) args.get("file_path");

        if (sessionId == null) return error("session_id is required");
        if (sql == null || sql.isBlank()) return error("sql is required");
        if (filePath == null || filePath.isBlank()) return error("file_path is required");

        ConnectionSession session = SessionManager.get(sessionId);
        if (session == null) return error("Session not found: " + sessionId);

        Integer maxRowsArg = asInt(args.get("max_rows"));
        int maxRows = (maxRowsArg != null && maxRowsArg > 0) ? maxRowsArg : Config.defaultMaxRows();

        session.beginCall();
        try (Statement st = session.getProxyConnection().createStatement()) {
            ExecuteQueryTool.applyLimits(st, Config.queryTimeoutSeconds(), maxRows);
            try (ResultSet rs = st.executeQuery(sql)) {
                ResultSetSerializer.SerializedResult result = ResultSetSerializer.serialize(rs, maxRows);
                session.setLastCallRowCount(result.rows().size());
                session.endCall(result.rows().size(), 0);

                Path path = Path.of(filePath);
                if (path.getParent() != null) Files.createDirectories(path.getParent());

                List<String> headers = new java.util.ArrayList<>();
                for (Map<String, Object> col : result.columns()) headers.add(String.valueOf(col.get("name")));

                try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                    w.write(toCsvRow(headers.toArray(new String[0])));
                    w.write("\r\n");
                    for (Map<String, Object> row : result.rows()) {
                        String[] cells = new String[headers.size()];
                        for (int i = 0; i < headers.size(); i++) {
                            cells[i] = renderCell(row.get(headers.get(i)));
                        }
                        w.write(toCsvRow(cells));
                        w.write("\r\n");
                    }
                }

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("file_path", path.toAbsolutePath().toString());
                response.put("row_count", result.rows().size());
                response.put("column_count", headers.size());
                response.put("truncated", result.truncated());
                return ok(response);
            }
        } catch (Exception e) {
            return error("export_results failed: " + e.getMessage());
        }
    }

    /** Render a serialized cell to a flat CSV string (handling the binary/truncated-text wrappers). */
    @SuppressWarnings("unchecked")
    private static String renderCell(Object v) {
        if (v == null) return "";
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> map = (Map<String, Object>) m;
            if ("base64".equals(map.get("_encoding"))) {
                return "[base64:" + map.get("byte_length") + " bytes]";
            }
            // truncated text wrapper
            Object data = map.get("data");
            return data != null ? data.toString() : map.toString();
        }
        return v.toString();
    }

    private static String toCsvRow(String[] cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(quote(cells[i]));
        }
        return sb.toString();
    }

    /** RFC4180 quoting: wrap in quotes and double internal quotes when the value needs it. */
    private static String quote(String s) {
        if (s == null) s = "";
        boolean needs = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        if (!needs) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
