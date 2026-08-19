package com.cdata.mcp.jdbc;

import com.cdata.mcp.config.Config;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ResultSetSerializer {

    // LocalDateTime/LocalTime.toString() omits seconds (and smaller) when they are zero, so seconds
    // are always written explicitly. Fractional seconds are appended only when present: a plain
    // "HH:mm:ss" pattern silently discarded them, which made millisecond-precision values compare
    // equal to each other and to whole seconds — the exact class of bug this tool exists to catch.
    private static final DateTimeFormatter TIMESTAMP_FMT = new DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .toFormatter();
    private static final DateTimeFormatter TIME_FMT = new DateTimeFormatterBuilder()
            .appendPattern("HH:mm:ss")
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .toFormatter();

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

    /**
     * Convert one cell using the same typing rules as {@link #serialize}, for callers that stream
     * rows rather than materializing them (export_results).
     */
    public static Object cellValue(ResultSet rs, int col, int jdbcType) throws SQLException {
        return getTypedValue(rs, col, jdbcType, Config.maxCellBytes());
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
            // Only REAL is single-precision. JDBC maps FLOAT to double, so reading it with
            // getFloat() rounded every SQL FLOAT column to ~7 digits before any assertion saw it.
            case Types.REAL -> {
                float v = rs.getFloat(col);
                yield rs.wasNull() ? null : v;
            }
            case Types.FLOAT, Types.DOUBLE -> {
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
                yield v != null ? v.toLocalTime().format(TIME_FMT) : null;
            }
            case Types.TIMESTAMP -> {
                Timestamp v = rs.getTimestamp(col);
                yield v != null ? v.toLocalDateTime().format(TIMESTAMP_FMT) : null;
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

    /**
     * Large text is returned whole when small, or capped with metadata when it exceeds the limit.
     * The budget is UTF-8 bytes, matching the property name and the binary path — comparing
     * {@code String.length()} against a byte budget let multi-byte text exceed it several times over.
     */
    private static Object truncateText(String s, int maxCellBytes) {
        if (s == null) return null;
        // Fast path: even at UTF-8's worst case (4 bytes/char) this cannot exceed the budget.
        if (s.length() <= maxCellBytes / 4) return s;
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        if (utf8.length <= maxCellBytes) return s;

        String slice = prefixWithinBytes(s, maxCellBytes);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("_truncated", true);
        m.put("char_length", s.length());
        m.put("byte_length", utf8.length);
        m.put("data", slice);
        return m;
    }

    /** Longest prefix of {@code s} whose UTF-8 encoding fits in {@code maxBytes}, on a char boundary. */
    private static String prefixWithinBytes(String s, int maxBytes) {
        int lo = 0, hi = Math.min(s.length(), maxBytes);
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (s.substring(0, mid).getBytes(StandardCharsets.UTF_8).length <= maxBytes) lo = mid;
            else hi = mid - 1;
        }
        return s.substring(0, lo);
    }
}
