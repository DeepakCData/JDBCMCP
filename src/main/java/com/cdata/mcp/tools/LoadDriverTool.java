package com.cdata.mcp.tools;

import com.cdata.mcp.jdbc.DriverLoader;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;

import static com.cdata.mcp.tools.JsonUtil.*;

public class LoadDriverTool {

    public static McpSchema.Tool tool() {
        return McpSchema.Tool.builder()
                .name("load_driver")
                .description("""
                        Load a JDBC driver JAR at runtime. Provide ONE of:
                          • driver_name  — short CData name (e.g. "sharepoint", "salesforce", "saperp") — class resolved automatically.
                          • driver_class — fully-qualified class (e.g. "cdata.jdbc.sharepoint.SharePointDriver").
                        If neither is provided, the JAR is scanned via ServiceLoader then by trying all known CData class names.
                        Known short names: acumatica, bigquery, box, dynamics365, dynamicscrm, excel, googledrive, googlesheets,
                          hubspot, jira, marketo, mongodb, mysql, netsuite, odatadriver, oracle, oraclesalescloud,
                          outreach, paypal, postgresql, rest, saperp, salesforce, servicenow, sharepoint,
                          slack, snowflake, sqlserver, stripe, xero, zendesk, zohocrm.""")
                .inputSchema(schema(
                        Map.of(
                                "jar_path",    strProp("Absolute path to the JDBC driver .jar file"),
                                "driver_name", strProp("(Optional) Short CData driver name, e.g. 'sharepoint'. Class resolved automatically."),
                                "driver_class", strProp("(Optional) Fully-qualified Driver class, e.g. cdata.jdbc.sharepoint.SharePointDriver")
                        ),
                        List.of("jar_path")
                ))
                .build();
    }

    public static McpSchema.CallToolResult handle(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments();
        String jarPath     = (String) args.get("jar_path");
        String driverName  = (String) args.get("driver_name");
        String driverClass = (String) args.get("driver_class");

        if (jarPath == null || jarPath.isBlank()) return error("jar_path is required");

        try {
            String loaded;
            String resolvedFrom;
            if (driverClass != null && !driverClass.isBlank()) {
                loaded = DriverLoader.loadByClass(jarPath, driverClass);
                resolvedFrom = "driver_class";
            } else if (driverName != null && !driverName.isBlank()) {
                loaded = DriverLoader.loadByName(jarPath, driverName);
                resolvedFrom = "driver_name:" + driverName;
            } else {
                loaded = DriverLoader.load(jarPath);
                resolvedFrom = "ServiceLoader / scan";
            }
            // driver_class reports the class actually registered (not the requested name).
            return ok(Map.of("status", "loaded", "driver_class", loaded, "resolved_from", resolvedFrom));
        } catch (Exception e) {
            return error("Failed to load driver: " + e.getMessage());
        }
    }
}
