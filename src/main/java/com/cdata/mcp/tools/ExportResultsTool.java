package com.cdata.mcp.tools;

import com.cdata.mcp.config.Config;
import com.cdata.mcp.jdbc.ConnectionSession;
import com.cdata.mcp.jdbc.QueryBudget;
import com.cdata.mcp.jdbc.ResultSetSerializer;
import com.cdata.mcp.jdbc.SessionManager;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
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
                        larger max_rows for full exports — rows stream straight to the file, so a large export
                        does not have to fit in memory.
                        Refuses to overwrite an existing file unless overwrite=true.""")
                .inputSchema(schema(
                        Map.of(
                                "session_id", strProp("Session ID from connect"),
                                "sql",        strProp("SQL SELECT statement to export"),
                                "file_path",  strProp("Absolute path of the CSV file to write, e.g. C:\\\\temp\\\\out.csv"),
                                "max_rows",   intProp("(Optional) Maximum rows to export. Defaults to server config."),
                                "timeout_seconds", intProp("(Optional) Query timeout in seconds. Defaults to server config."),
                                "overwrite",  boolProp("(Optional) Allow replacing an existing file at file_path. Default false.")
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
        boolean overwrite = asBool(args.get("overwrite"), false);

        Path path = Path.of(filePath);
        // Writing a CSV used to truncate whatever was already at file_path. An agent-supplied path is
        // easy to get wrong, and the previous contents were unrecoverable, so replacing a file is now
        // something the caller has to ask for.
        if (Files.exists(path) && !overwrite) {
            return error("Refusing to overwrite existing file: " + path.toAbsolutePath()
                    + ". Pass overwrite=true to replace it, or choose another file_path.");
        }

        session.beginCall();
        try (Statement st = session.getProxyConnection().createStatement();
             QueryBudget.Disarm disarm =
                     ExecuteQueryTool.applyLimits(session, st, ExecuteQueryTool.resolveTimeout(args), maxRows)) {
            try (ResultSet rs = st.executeQuery(sql)) {
                if (path.getParent() != null) Files.createDirectories(path.getParent());

                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                String[] headers = new String[colCount];
                int[] types = new int[colCount];
                for (int i = 1; i <= colCount; i++) {
                    headers[i - 1] = meta.getColumnLabel(i);
                    types[i - 1] = meta.getColumnType(i);
                }

                int rowCount = 0;
                boolean truncated = false;
                // Rows go straight to the writer. Materializing the whole result set first meant a
                // "full export" with a large max_rows had to fit in the heap before a byte was written.
                try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                    w.write(toCsvRow(headers));
                    w.write("\r\n");
                    while (rs.next()) {
                        if (rowCount >= maxRows) { truncated = true; break; }
                        String[] cells = new String[colCount];
                        for (int i = 1; i <= colCount; i++) {
                            cells[i - 1] = renderCell(ResultSetSerializer.cellValue(rs, i, types[i - 1]));
                        }
                        w.write(toCsvRow(cells));
                        w.write("\r\n");
                        rowCount++;
                    }
                }

                session.setLastCallRowCount(rowCount);
                session.endCall(rowCount, 0);

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("file_path", path.toAbsolutePath().toString());
                response.put("row_count", rowCount);
                response.put("column_count", colCount);
                response.put("truncated", truncated);
                return ok(response);
            }
        } catch (Exception e) {
            return errorWithTrace("export_results failed: " + describe(e), session);
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
