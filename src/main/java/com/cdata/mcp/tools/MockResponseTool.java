package com.cdata.mcp.tools;

import com.cdata.mcp.config.Config;
import com.cdata.mcp.mitm.MockRules;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.cdata.mcp.tools.JsonUtil.*;

/**
 * Rewrites backend HTTP responses in flight, so a test can force an edge case the live account
 * will not produce on demand.
 *
 * <p>Plenty of driver bugs only appear on a response shape the backend rarely returns: a field
 * missing from the first record (DRIVERS-63278), an empty page, a 429, a call slow enough to trip
 * a timeout. Reproducing those used to mean an external autoresponder, which is why such tickets
 * were verified against the fix description rather than against the driver.
 *
 * <p>Rules go to a file the mitmproxy addon reloads on change, so they apply to the next backend
 * call on an open session with no reconnect — and to every session on this server, since one
 * mitmdump serves them all. That sharing is also the hazard: a rule left armed rewrites someone
 * else's run into a confident wrong verdict. Hence rules are cleared at server start, every mutated
 * response is marked in the capture log, and the active count rides along in each query's _meta.
 */
public class MockResponseTool {

    public static McpSchema.Tool tool() {
        return McpSchema.Tool.builder()
                .name("mock_response")
                .description("""
                        Rewrite backend HTTP responses in flight, to force an edge case the live account will
                        not produce on demand — a field missing from the first record, an empty page, a 429,
                        a call slow enough to trip a timeout.

                        Only works on the proxied path (connect reported proxy_applied=true). Rules apply to the
                        NEXT backend call — no reconnect needed — and affect every session on this server.

                        actions:
                          • add    — arm one rule (match + ops). Returns the full armed set.
                          • list   — show armed rules.
                          • clear  — disarm all rules, or just rule_id. ALWAYS clear when the test is done.

                        A rule is {match, ops, id?, max_hits?}:
                          match — at least one of url_contains, url_regex, method, status, body_contains.
                                  A rule matching everything is refused; it would rewrite unrelated traffic.
                          ops   — applied in order. JSON-body ops use a dotted path with [n] / [*]:
                                    {"op":"remove","path":"results[0].subscriptionDetails.subscriptionId"}
                                    {"op":"set","path":"results[*].status","value":"ARCHIVED"}  (creates the key if absent)
                                    {"op":"move_to_front","path":"results","where_missing":"subscriptionDetails.subscriptionId"}
                                    {"op":"truncate","path":"results","keep":0}
                                  Response-level ops:
                                    {"op":"status","value":429}        {"op":"header","name":"X-Foo","value":"bar"}
                                    {"op":"body","value":"not json"}   {"op":"delay","ms":5000}
                          max_hits — stop applying after N matching responses (0 = unlimited).

                        Every mutated response is recorded in the capture log with a "mock" block naming the
                        rule and what it changed, so evidence shows which rows were real and which were forced.
                        Read it over the call's capture_from–capture_to range as usual. An op reporting
                        affected:0 matched nothing — the path is probably wrong, and the driver saw live data.""")
                .inputSchema(schema(
                        Map.of(
                                "action",   strProp("add | list | clear"),
                                "id",       strProp("(Optional, add) Rule label used in the capture log and in clear. Auto-generated when omitted."),
                                "match",    Map.of("type", "object",
                                        "description", "(add) Which responses to rewrite. At least one of url_contains, url_regex, method, status, body_contains."),
                                "ops",      Map.of("type", "array", "items", Map.of("type", "object"),
                                        "description", "(add) Operations applied in order. See the tool description for the shapes."),
                                "max_hits", intProp("(Optional, add) Stop after N matching responses. 0 or omitted = unlimited."),
                                "rule_id",  strProp("(Optional, clear) Disarm only this rule. Omit to disarm everything.")
                        ),
                        List.of("action")
                ))
                .build();
    }

    @SuppressWarnings("unchecked")
    public static McpSchema.CallToolResult handle(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments();
        String action = args.get("action") == null ? "" : String.valueOf(args.get("action")).trim().toLowerCase();

        try {
            switch (action) {
                case "add" -> {
                    Object match = args.get("match");
                    Object ops = args.get("ops");
                    if (match == null) return error("match is required for action=add");
                    if (ops == null) return error("ops is required for action=add");

                    List<Map<String, Object>> rules = MockRules.load();

                    Map<String, Object> rule = new LinkedHashMap<>();
                    String id = asStr(args.get("id"));
                    if (id == null || id.isBlank()) id = "rule" + (rules.size() + 1);
                    rule.put("id", id);
                    rule.put("enabled", true);
                    rule.put("match", match);
                    rule.put("ops", ops);
                    Integer maxHits = asInt(args.get("max_hits"));
                    if (maxHits != null && maxHits > 0) rule.put("max_hits", maxHits);

                    String problem = MockRules.validate(rule);
                    if (problem != null) return error("Invalid rule: " + problem);

                    // Re-arming an id replaces it, so iterating on a rule does not silently stack
                    // two versions that both fire.
                    final String ruleId = id;
                    rules.removeIf(r -> ruleId.equals(r.get("id")));
                    rules.add(rule);
                    MockRules.save(rules);

                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("armed", true);
                    out.put("rule_id", id);
                    out.put("active_rules", rules.size());
                    out.put("rules", rules);
                    out.put("rules_path", MockRules.path().toString());
                    out.put("applies_to", "the next backend call on any session of this server — no reconnect needed");
                    out.put("evidence", "mutated responses carry a \"mock\" block in " + Config.mitmLogPath());
                    out.put("reminder", "call mock_response action=clear when this test is done; "
                            + "a rule left armed rewrites later, unrelated runs");
                    return ok(out);
                }

                case "list" -> {
                    List<Map<String, Object>> rules = MockRules.load();
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("active_rules", rules.size());
                    out.put("rules", rules);
                    out.put("rules_path", MockRules.path().toString());
                    return ok(out);
                }

                case "clear" -> {
                    String ruleId = asStr(args.get("rule_id"));
                    if (ruleId == null || ruleId.isBlank()) {
                        int n = MockRules.clear();
                        return ok(Map.of("cleared", n, "active_rules", 0));
                    }
                    List<Map<String, Object>> rules = MockRules.load();
                    List<Map<String, Object>> kept = new ArrayList<>(rules);
                    boolean removed = kept.removeIf(r -> ruleId.equals(r.get("id")));
                    if (!removed) return error("No armed rule with id '" + ruleId + "'");
                    if (kept.isEmpty()) MockRules.clear(); else MockRules.save(kept);
                    return ok(Map.of("cleared", 1, "rule_id", ruleId, "active_rules", kept.size(), "rules", kept));
                }

                default -> {
                    return error("action must be one of: add, list, clear");
                }
            }
        } catch (Exception e) {
            return error("mock_response failed: " + describe(e));
        }
    }
}
