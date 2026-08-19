package com.cdata.mcp.log;

import com.cdata.mcp.config.Config;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Housekeeping for the capture artifacts this server leaves in the system temp directory:
 * per-session CData driver logs ({@code jdbc_mcp_<session>.log}, plus the driver's own ~100 MB
 * rotations) and the mitmproxy JSONL capture.
 *
 * Neither used to be cleaned up, with two consequences: months of QA runs accumulated into
 * gigabytes of temp files, and the single append-only JSONL mixed traffic from unrelated
 * sessions into one file — so paging into it landed on some earlier run's requests.
 *
 * Two mechanisms, both run once at server start:
 *   1. {@link #rotateMitmLog()} — moves a non-empty capture aside so each server run writes a
 *      fresh JSONL. The addon reopens the path per write, so this is safe while mitmdump runs.
 *   2. {@link #sweep()} — deletes driver logs and rotated captures older than the retention
 *      window, then trims oldest-first until the remaining footprint fits the size cap.
 */
public final class LogJanitor {

    private LogJanitor() {}

    /** Timestamp suffix for rotated captures: jdbc_mcp_proxy-20260819-114210.jsonl */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    /** What a sweep did — surfaced on stderr at boot so the footprint is never a mystery. */
    public record SweepResult(int filesDeleted, long bytesFreed, long bytesRemaining, String note) {
        public String summary() {
            return "log sweep: deleted " + filesDeleted + " file(s), freed " + mb(bytesFreed)
                    + " MB, " + mb(bytesRemaining) + " MB remaining" + (note.isEmpty() ? "" : " (" + note + ")");
        }
    }

    /**
     * Rotates the live mitmproxy capture so this server run starts with an empty one.
     *
     * @return a description of what happened, for the boot log
     */
    public static String rotateMitmLog() {
        if (!Config.logSweepEnabled()) return "rotation disabled";

        Path live = Path.of(Config.mitmLogPath());
        try {
            if (!Files.exists(live) || Files.size(live) == 0L) return "capture log already empty";

            String name = live.getFileName().toString();
            String base = name.endsWith(".jsonl") ? name.substring(0, name.length() - ".jsonl".length()) : name;
            Path archive = live.resolveSibling(base + "-" + STAMP.format(Instant.now()) + ".jsonl");

            long size = Files.size(live);
            Files.move(live, archive);
            return "rotated capture log (" + mb(size) + " MB) to " + archive.getFileName();
        } catch (IOException e) {
            // A still-running mitmdump from a previous run can hold the handle on Windows. Not fatal:
            // capture keeps working, callers just have to seek past the older entries (mitm_log_offset).
            return "capture log NOT rotated (" + e.getClass().getSimpleName() + ") — reads must use mitm_log_offset";
        }
    }

    /**
     * Deletes stale driver logs and rotated captures, then enforces the total-size cap.
     * Only ever touches files this server created, and never the live capture log.
     */
    public static SweepResult sweep() {
        if (!Config.logSweepEnabled()) return new SweepResult(0, 0, 0, "sweep disabled");

        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        Path liveCapture = Path.of(Config.mitmLogPath()).toAbsolutePath().normalize();

        List<File> candidates = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(tmp, "jdbc_mcp_*")) {
            for (Path p : entries) {
                if (!isOurs(p)) continue;
                if (p.toAbsolutePath().normalize().equals(liveCapture)) continue;
                if (!Files.isRegularFile(p)) continue;
                candidates.add(p.toFile());
            }
        } catch (IOException e) {
            return new SweepResult(0, 0, 0, "could not scan temp dir: " + e.getClass().getSimpleName());
        }

        long cutoff = System.currentTimeMillis() - Config.logRetentionDays() * 86_400_000L;
        int deleted = 0;
        long freed = 0;

        // Pass 1 — age.
        for (File f : new ArrayList<>(candidates)) {
            if (f.lastModified() < cutoff) {
                long size = f.length();
                if (f.delete()) {
                    deleted++;
                    freed += size;
                    candidates.remove(f);
                }
            }
        }

        // Pass 2 — size cap, oldest first. Age alone can't hold the line: a single verbose session
        // can leave half a gigabyte behind inside the retention window.
        long remaining = 0;
        for (File f : candidates) remaining += f.length();
        long cap = Config.logMaxTotalMb() * 1024L * 1024L;
        if (remaining > cap) {
            candidates.sort(Comparator.comparingLong(File::lastModified));
            for (File f : candidates) {
                if (remaining <= cap) break;
                long size = f.length();
                if (f.delete()) {
                    deleted++;
                    freed += size;
                    remaining -= size;
                }
            }
        }

        return new SweepResult(deleted, freed, remaining, "");
    }

    /**
     * Deletes one session's driver log and any rotations the CData driver made off it
     * ({@code jdbc_mcp_<session>-<stamp>.log}), which are the 100 MB files.
     *
     * @return bytes reclaimed (0 if there was nothing to delete)
     */
    public static long deleteSessionLog(String logfilePath) {
        if (logfilePath == null || logfilePath.isBlank()) return 0L;

        Path log = Path.of(logfilePath);
        Path dir = log.getParent();
        String name = log.getFileName().toString();
        if (dir == null || !name.startsWith("jdbc_mcp_") || !name.endsWith(".log")) return 0L;

        String stem = name.substring(0, name.length() - ".log".length());
        long freed = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, stem + "*.log")) {
            for (Path p : entries) {
                File f = p.toFile();
                long size = f.length();
                if (f.delete()) freed += size;
            }
        } catch (IOException ignored) {
            // Best effort — a locked log is left for the next startup sweep.
        }
        return freed;
    }

    /** True for artifacts this server owns: per-session driver logs and mitmproxy captures. */
    private static boolean isOurs(Path p) {
        String n = p.getFileName().toString();
        return n.startsWith("jdbc_mcp_") && (n.endsWith(".log") || n.endsWith(".jsonl"));
    }

    private static String mb(long bytes) {
        return String.format("%.1f", bytes / (1024.0 * 1024.0));
    }
}
