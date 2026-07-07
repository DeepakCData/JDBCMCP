package com.cdata.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class JsonUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"error\":\"serialization failed: " + e.getMessage() + "\"}";
        }
    }

    public static McpSchema.CallToolResult ok(Object responseMap) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(toJson(responseMap))
                .isError(false)
                .build();
    }

    public static McpSchema.CallToolResult error(String message) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(toJson(Map.of("error", message)))
                .isError(true)
                .build();
    }

    public static McpSchema.JsonSchema schema(Map<String, Object> properties, List<String> required) {
        return new McpSchema.JsonSchema("object", properties, required, false, null, null);
    }

    /** Simple string property descriptor. */
    public static Map<String, Object> strProp(String description) {
        return Map.of("type", "string", "description", description);
    }

    /** Integer property descriptor. */
    public static Map<String, Object> intProp(String description) {
        return Map.of("type", "integer", "description", description);
    }

    /** Boolean property descriptor. */
    public static Map<String, Object> boolProp(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    /** Untyped property descriptor — accepts any JSON scalar (string, number, boolean). */
    public static Map<String, Object> anyProp(String description) {
        return Map.of("description", description);
    }

    // ------------------------------------------------------------------
    // Robust argument coercion — MCP clients may send numbers as Integer,
    // Long, Double, or even strings depending on the transport.
    // ------------------------------------------------------------------

    public static Integer asInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(o.toString().trim()); } catch (Exception e) { return null; }
    }

    public static boolean asBool(Object o, boolean def) {
        if (o == null) return def;
        if (o instanceof Boolean b) return b;
        return Boolean.parseBoolean(o.toString().trim());
    }

    public static String asStr(Object o) {
        return o == null ? null : o.toString();
    }

    // ------------------------------------------------------------------
    // Credential redaction — keeps secrets in connection strings out of
    // echoed errors and logs. Matches key=value pairs for sensitive keys.
    // ------------------------------------------------------------------

    private static final Pattern SECRET = Pattern.compile(
            "(?i)\\b(password|pwd|secret|clientsecret|client_secret|token|authtoken|auth_token|accesstoken|access_token|"
            + "refreshtoken|refresh_token|apikey|api_key|privatekey|private_key|oauthclientsecret|connectionpassword)"
            + "\\s*=\\s*[^;]*");

    /** Replaces the value of any sensitive key in a connection-string-like text with ***. */
    public static String redact(String text) {
        if (text == null) return null;
        return SECRET.matcher(text).replaceAll(m -> m.group(1) + "=***");
    }
}
