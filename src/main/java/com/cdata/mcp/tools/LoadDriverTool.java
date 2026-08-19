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
                        If neither is provided, the class the JAR declares in META-INF/services/java.sql.Driver is
                        used, falling back to the known CData class names.

                        The registered class is always one defined by the JAR you passed. A class that resolves to a
                        copy on the server's own classpath is rejected rather than reported as loaded, so a successful
                        response means the driver under test is what got registered.

                        Known short names: acumatica, bigquery, box, csv, dynamics365, dynamicscrm, excel, googledrive,
                          googlesheets, hubspot, jira, marketo, mongodb, mysql, netsuite, odatadriver, oracle,
                          oraclesalescloud, outreach, paypal, postgresql, rest, saperp, salesforce, servicenow,
                          sfmarketingcloud, sharepoint, slack, snowflake, sqlserver, stripe, xero, zendesk, zohocrm.
                        A name outside this list is tried as cdata.jdbc.<name>.<Name>Driver, then matched against the
                        JAR's own declaration — so unusual capitalization (CSVDriver, SFMarketingCloudDriver) resolves
                        without needing driver_class.""")
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
                resolvedFrom = "jar_declaration / scan";
            }
            // driver_class reports the class actually registered (not the requested name).
            return ok(Map.of("status", "loaded", "driver_class", loaded, "resolved_from", resolvedFrom));
        } catch (Exception e) {
            return error("Failed to load driver: " + describe(e));
        }
    }
}
