package com.cdata.mcp.jdbc;

import com.cdata.mcp.config.Config;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads byte ranges out of the mitmproxy capture log.
 *
 * <p>The capture is append-only and shared by every session in a server run, so "find the requests
 * my query caused" used to mean searching by timestamp — which requires knowing when the query ran,
 * converting from the log's UTC, and hoping no other session was in flight. A byte range answers it
 * exactly instead: the offset is sampled when a tool call begins and again when it ends, so
 * everything between the two belongs to that call and nothing else.
 */
public final class CaptureLog {

    private CaptureLog() {}

    /** Never read more than this in one go; diagnosing a slow call must not become a second stall. */
    private static final long MAX_SCAN_BYTES = 32L * 1024 * 1024;

    private static final Pattern DURATION = Pattern.compile("\"duration_ms\"\\s*:\\s*(\\d+)");

    /** Entries in a byte range, and the total round-trip time they report. */
    public record Scan(int entries, long totalMs) {}

    /** Current length of the live capture, 0 when it does not exist yet, or -1 if unreadable. */
    public static long currentLength() {
        try {
            Path p = Path.of(Config.mitmLogPath());
            return Files.exists(p) ? Files.size(p) : 0L;
        } catch (Exception e) {
            return -1L;
        }
    }

    /**
     * Count entries between two offsets and sum their round-trip times.
     *
     * @param from starting byte offset; negative means "no range recorded"
     * @param to   ending byte offset, or negative for end-of-file
     * @return the scan, or null when the range cannot be read (no capture, range too large)
     */
    public static Scan scan(long from, long to) {
        if (from < 0) return null;
        try {
            Path log = Path.of(Config.mitmLogPath());
            if (!Files.exists(log)) return new Scan(0, 0);
            long size = Files.size(log);
            long end = (to < 0 || to > size) ? size : to;
            if (end <= from) return new Scan(0, 0);
            long span = end - from;
            if (span > MAX_SCAN_BYTES) return null;

            byte[] buf = new byte[(int) span];
            try (RandomAccessFile raf = new RandomAccessFile(log.toFile(), "r")) {
                raf.seek(from);
                raf.readFully(buf);
            }

            int entries = 0;
            long totalMs = 0;
            // One JSON object per line. Counting newlines is enough, and avoids parsing bodies
            // that can be tens of kilobytes each.
            for (String line : new String(buf, StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank()) continue;
                entries++;
                Matcher m = DURATION.matcher(line);
                if (m.find()) {
                    try { totalMs += Long.parseLong(m.group(1)); } catch (NumberFormatException ignored) {}
                }
            }
            return new Scan(entries, totalMs);
        } catch (Exception e) {
            // Best effort: a capture we cannot read must never break the call it describes.
            return null;
        }
    }
}
