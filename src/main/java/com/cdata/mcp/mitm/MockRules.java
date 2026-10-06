package com.cdata.mcp.mitm;

import com.cdata.mcp.config.Config;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The response-mocking rules shared with the mitmproxy addon.
 *
 * <p>Rules live in a JSON file rather than in this process, because one mitmdump serves every
 * session in a server run: restarting it to pick up a rule would cut the other sessions off. The
 * addon stats the file on each response and reloads it when it changes, so a rule takes effect on
 * the next backend call with no reconnect.
 *
 * <p>A stale rule is the dangerous failure mode — it silently rewrites a later, unrelated QA run
 * into a confident wrong verdict. Three things guard against it: the file is cleared at server
 * start, every mutated response is marked in the capture log, and {@link #activeCount()} is
 * reported in the {@code _meta} of every query while any rule is armed.
 */
public final class MockRules {

    private MockRules() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Ops that act on a parsed JSON body; the rest act on the HTTP response itself. */
    private static final List<String> KNOWN_OPS =
            List.of("remove", "set", "move_to_front", "truncate", "status", "header", "body", "delay");

    public static Path path() {
        return Path.of(Config.mockRulesPath());
    }

    /** Current rules, or an empty list when none are armed or the file is unreadable. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> load() {
        Path p = path();
        try {
            if (!Files.exists(p) || Files.size(p) == 0L) return new ArrayList<>();
            Map<String, Object> doc = MAPPER.readValue(Files.readString(p, StandardCharsets.UTF_8), Map.class);
            Object rules = doc.get("rules");
            if (rules instanceof List<?> list) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Object o : list) if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
                return out;
            }
        } catch (Exception ignored) {
            // A malformed file is treated as "no rules" here exactly as it is in the addon, so the
            // count this server reports can never claim fewer rules than are actually armed.
        }
        return new ArrayList<>();
    }

    /**
     * Replaces the rule set.
     *
     * <p>Written to a temp file and moved into place: the addon reloads on any change, and a
     * half-written file would otherwise be read as "no rules" mid-run.
     */
    public static void save(List<Map<String, Object>> rules) throws IOException {
        Path p = path();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("rules", rules);
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.writeString(tmp, MAPPER.writeValueAsString(doc), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static volatile String countStamp = null;
    private static volatile int cachedCount = 0;

    /**
     * Number of enabled rules currently armed.
     *
     * <p>Reported in the {@code _meta} of every query, so it is cached against the file's
     * mtime+size: the common case (no rules, file absent) costs one stat.
     */
    public static int activeCount() {
        Path p = path();
        String stamp;
        try {
            stamp = Files.exists(p) ? Files.getLastModifiedTime(p).toMillis() + ":" + Files.size(p) : "absent";
        } catch (IOException e) {
            stamp = "unreadable";
        }
        if (stamp.equals(countStamp)) return cachedCount;

        int n = 0;
        if (!"absent".equals(stamp)) {
            for (Map<String, Object> r : load()) {
                Object enabled = r.get("enabled");
                if (enabled == null || Boolean.TRUE.equals(enabled)) n++;
            }
        }
        cachedCount = n;
        countStamp = stamp;
        return n;
    }

    /** Removes every rule. Returns how many were armed. */
    public static int clear() throws IOException {
        int n = load().size();
        Files.deleteIfExists(path());
        return n;
    }

    /**
     * Clears rules left behind by a previous server run, for the boot log.
     *
     * <p>Rules are deliberately not persistent: surviving a restart is how a forgotten rule
     * silently rewrites someone else's QA run days later.
     */
    public static String clearStale() {
        try {
            int n = clear();
            return n == 0 ? "no stale mock rules" : "cleared " + n + " stale mock rule(s) from a previous run";
        } catch (IOException e) {
            return "could NOT clear stale mock rules (" + e.getClass().getSimpleName()
                    + ") — check " + path() + " before trusting any result";
        }
    }

    /**
     * Validates a rule, returning the problem or null when it is usable.
     *
     * <p>A rule that matches nothing fails open — the traffic passes through untouched and the test
     * quietly verifies live data instead of the edge case. Catching the typo here is the difference
     * between a rejected call and a PASS that means nothing.
     */
    @SuppressWarnings("unchecked")
    public static String validate(Map<String, Object> rule) {
        Object match = rule.get("match");
        if (!(match instanceof Map<?, ?> m) || m.isEmpty()) {
            return "match is required and must name at least one of url_contains, url_regex, method, status, body_contains";
        }
        boolean hasSelector = false;
        for (String k : List.of("url_contains", "url_regex", "method", "status", "body_contains")) {
            Object v = ((Map<String, Object>) m).get(k);
            if (v != null && !String.valueOf(v).isBlank()) hasSelector = true;
        }
        if (!hasSelector) {
            return "match must name at least one of url_contains, url_regex, method, status, body_contains — "
                    + "a rule that matches every response would rewrite unrelated traffic";
        }

        Object ops = rule.get("ops");
        if (!(ops instanceof List<?> list) || list.isEmpty()) {
            return "ops is required and must contain at least one operation";
        }
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> opMap)) return "each entry in ops must be an object";
            Object kind = opMap.get("op");
            String op = kind == null ? "" : String.valueOf(kind).toLowerCase();
            if (!KNOWN_OPS.contains(op)) {
                return "unknown op '" + kind + "' — supported: " + String.join(", ", KNOWN_OPS);
            }
            boolean needsPath = List.of("remove", "set", "move_to_front", "truncate").contains(op);
            if (needsPath && String.valueOf(opMap.get("path")).isBlank()) {
                return "op '" + op + "' requires a path, e.g. results[0].subscriptionDetails.subscriptionId";
            }
            if (op.equals("move_to_front")
                    && opMap.get("where_missing") == null
                    && opMap.get("where_present") == null
                    && opMap.get("index") == null) {
                return "op 'move_to_front' requires one of where_missing, where_present or index";
            }
            if (op.equals("status") && opMap.get("value") == null) {
                return "op 'status' requires a value, e.g. 429";
            }
            if (op.equals("header") && String.valueOf(opMap.get("name")).isBlank()) {
                return "op 'header' requires a name";
            }
        }
        return null;
    }
}
