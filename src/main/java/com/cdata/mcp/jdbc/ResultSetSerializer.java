package com.cdata.mcp.jdbc;

import com.cdata.mcp.config.Config;

import java.math.BigDecimal;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ResultSetSerializer {

    /** truncated = true when more rows were available than the row cap allowed. */
    public record SerializedResult(List<Map<String, Object>> rows,
                                   List<Map<String, Object>> columns,
                                   boolean truncated) {}

    /**
     * Serialize a result set, materializing at most {@code maxRows} rows.
     * A value of {@code <= 0} falls back to the configured default cap.
     */
    public static SerializedResult serialize(ResultSet rs, int maxRows) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();
        int cap = maxRows > 0 ? maxRows : Config.defaultMaxRows();
        int maxCellBytes = Config.maxCellBytes();

        List<Map<String, Object>> columns = new ArrayList<>(colCount);
        for (int i = 1; i <= colCount; i++) {
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("name", meta.getColumnLabel(i));
            col.put("type", meta.getColumnTypeName(i));
            col.put("jdbc_type", meta.getColumnType(i));
            columns.add(col);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        boolean truncated = false;
        while (rs.next()) {
            if (rows.size() >= cap) { truncated = true; break; }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= colCount; i++) {
                row.put(meta.getColumnLabel(i), getTypedValue(rs, i, meta.getColumnType(i), maxCellBytes));
            }
            rows.add(row);
        }
        return new SerializedResult(rows, columns, truncated);
    }

    private static Object getTypedValue(ResultSet rs, int col, int jdbcType, int maxCellBytes) throws SQLException {
        return switch (jdbcType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> {
                int v = rs.getInt(col);
                yield rs.wasNull() ? null : v;
            }
            case Types.BIGINT -> {
                long v = rs.getLong(col);
                yield rs.wasNull() ? null : v;
            }
            case Types.FLOAT, Types.REAL -> {
                float v = rs.getFloat(col);
                yield rs.wasNull() ? null : v;
            }
            case Types.DOUBLE -> {
                double v = rs.getDouble(col);
                yield rs.wasNull() ? null : v;
            }
            case Types.NUMERIC, Types.DECIMAL -> rs.getBigDecimal(col);
            case Types.BOOLEAN, Types.BIT -> {
                boolean v = rs.getBoolean(col);
                yield rs.wasNull() ? null : v;
            }
            // ISO-8601 with 'T' separator (java.sql.* toString uses a space / loses offset).
            case Types.DATE -> {
                Date v = rs.getDate(col);
                yield v != null ? v.toLocalDate().toString() : null;
            }
            case Types.TIME -> {
                Time v = rs.getTime(col);
                yield v != null ? v.toLocalTime().toString() : null;
            }
            case Types.TIMESTAMP -> {
                Timestamp v = rs.getTimestamp(col);
                yield v != null ? v.toLocalDateTime().toString() : null;
            }
            case Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE -> {
                try {
                    OffsetDateTime o = rs.getObject(col, OffsetDateTime.class);
                    yield o != null ? o.toString() : null;
                } catch (Exception e) {
                    yield rs.getString(col);
                }
            }
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB ->
                    encodeBytes(rs, col, maxCellBytes);
            case Types.CLOB, Types.NCLOB, Types.LONGVARCHAR, Types.LONGNVARCHAR ->
                    truncateText(rs.getString(col), maxCellBytes);
            default -> truncateText(rs.getString(col), maxCellBytes);
        };
    }

    /** Binary columns are base64-encoded and capped; the original byte length is preserved. */
    private static Object encodeBytes(ResultSet rs, int col, int maxCellBytes) throws SQLException {
        byte[] bytes = rs.getBytes(col);
        if (bytes == null) return null;
        boolean truncated = bytes.length > maxCellBytes;
        byte[] slice = truncated ? Arrays.copyOf(bytes, maxCellBytes) : bytes;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("_encoding", "base64");
        m.put("byte_length", bytes.length);
        if (truncated) m.put("_truncated", true);
        m.put("data", Base64.getEncoder().encodeToString(slice));
        return m;
    }

    /** Large text is returned whole when small, or capped with metadata when it exceeds the limit. */
    private static Object truncateText(String s, int maxCellBytes) {
        if (s == null) return null;
        if (s.length() <= maxCellBytes) return s;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("_truncated", true);
        m.put("char_length", s.length());
        m.put("data", s.substring(0, maxCellBytes));
        return m;
    }
}
