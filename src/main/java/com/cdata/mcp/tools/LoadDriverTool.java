package com.cdata.mcp.tools;

import com.cdata.mcp.jdbc.DriverLoader;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

                        SOME DRIVERS NEED TWO JARS. A CData connector that wraps a vendor driver cannot work from
                        its own JAR alone — Oracle OCI and other native/thin wrappers need the vendor's JDBC or
                        client JAR (ojdbc11.jar, an Instant Client JAR, a proprietary DB2/Informix JAR) on the same
                        classloader. Pass the CData JAR as jar_path and the vendor JAR(s) in extra_jars:

                          jar_path:   C:\\Program Files\\CData\\...\\lib\\cdata.jdbc.oracleoci.jar
                          extra_jars: ["C:\\path\\to\\ojdbc11.jar"]

                        Use extra_jars whenever the engineer hands you more than one JAR for a single driver, or
                        when a connect attempt fails with a missing class (ClassNotFoundException /
                        NoClassDefFoundError / UnsatisfiedLinkError) naming a vendor package — connect's error
                        says so explicitly when it detects one. extra_jars only supplies dependencies; the
                        registered driver still has to come from one of the JARs you passed.

                        The registered class is always one defined by a JAR you passed. A class that resolves to a
                        copy on the server's own classpath is rejected rather than reported as loaded, so a
                        successful response means the driver under test is what got registered. The response
                        reports driver_jar — which JAR the class actually came from — and jars_loaded.

                        Known short names: acumatica, bigquery, box, csv, dynamics365, dynamicscrm, excel, googledrive,
                          googlesheets, hubspot, jira, marketo, mongodb, mysql, netsuite, odatadriver, oracle,
                          oracleoci, oraclesalescloud, outreach, paypal, postgresql, rest, saperp, salesforce,
                          servicenow, sfmarketingcloud, sharepoint, slack, snowflake, sqlserver, stripe, xero,
                          zendesk, zohocrm.
                        A name outside this list is tried as cdata.jdbc.<name>.<Name>Driver, then matched against the
                        JAR's own declaration — so unusual capitalization (CSVDriver, SFMarketingCloudDriver) resolves
                        without needing driver_class.""")
                .inputSchema(schema(
                        Map.of(
                                "jar_path",    strProp("Absolute path to the JDBC driver .jar file (the CData JAR, when there is a companion)"),
                                "driver_name", strProp("(Optional) Short CData driver name, e.g. 'sharepoint'. Class resolved automatically."),
                                "driver_class", strProp("(Optional) Fully-qualified Driver class, e.g. cdata.jdbc.sharepoint.SharePointDriver"),
                                "extra_jars",  Map.of(
                                        "type",        "array",
                                        "description", "(Optional) Companion JARs the driver needs on the same classloader — e.g. ojdbc11.jar for Oracle OCI. Dependencies only; the driver class must still come from one of the supplied JARs.",
                                        "items",       Map.of("type", "string"))
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

        List<String> jars = new ArrayList<>();
        jars.add(jarPath);
        Object extra = args.get("extra_jars");
        if (extra instanceof List<?> list) {
            for (Object o : list) if (o != null && !o.toString().isBlank()) jars.add(o.toString());
        } else if (extra instanceof String s && !s.isBlank()) {
            jars.add(s);   // tolerate a single string where an array was expected
        }

        try {
            DriverLoader.Loaded loaded;
            String resolvedFrom;
            if (driverClass != null && !driverClass.isBlank()) {
                loaded = DriverLoader.loadByClass(jars, driverClass);
                resolvedFrom = "driver_class";
            } else if (driverName != null && !driverName.isBlank()) {
                loaded = DriverLoader.loadByName(jars, driverName);
                resolvedFrom = "driver_name:" + driverName;
            } else {
                loaded = DriverLoader.load(jars);
                resolvedFrom = "jar_declaration / scan";
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "loaded");
            // driver_class is the class actually registered (not the requested name), and driver_jar
            // is which of the supplied JARs defined it — the two facts that say what is under test.
            response.put("driver_class", loaded.driverClass());
            response.put("driver_jar", loaded.driverJar());
            response.put("jars_loaded", loaded.jars());
            response.put("resolved_from", resolvedFrom);
            return ok(response);
        } catch (Exception e) {
            // ClassNotFoundException's message is the bare class name, which read as
            // "Failed to load driver: oracle.jdbc.OracleDriver" — a name, with no stated problem.
            String detail = (e instanceof ClassNotFoundException)
                    ? "class not found in the supplied JAR(s): " + e.getMessage()
                    : describe(e);
            String message = "Failed to load driver: " + detail;
            String hint = DriverLoader.companionJarHint(e);
            if (hint != null) message += " " + hint;
            return error(message);
        }
    }
}
