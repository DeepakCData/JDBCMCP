package com.cdata.mcp.jdbc;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class DriverLoader {

    /** Known CData JDBC driver class names keyed by short driver name (lowercase). */
    public static final Map<String, String> KNOWN_DRIVERS = Map.ofEntries(
            Map.entry("acumatica",        "cdata.jdbc.acumatica.AcumaticaDriver"),
            Map.entry("bigquery",         "cdata.jdbc.googlebigquery.GoogleBigQueryDriver"),
            Map.entry("box",              "cdata.jdbc.box.BoxDriver"),
            Map.entry("csv",              "cdata.jdbc.csv.CSVDriver"),
            Map.entry("dynamics365",      "cdata.jdbc.dynamics365.Dynamics365Driver"),
            Map.entry("dynamicscrm",      "cdata.jdbc.dynamicscrm.DynamicsCRMDriver"),
            Map.entry("excel",            "cdata.jdbc.excel.ExcelDriver"),
            Map.entry("googledrive",      "cdata.jdbc.googledrive.GoogleDriveDriver"),
            Map.entry("googlesheets",     "cdata.jdbc.googlesheets.GoogleSheetsDriver"),
            Map.entry("hubspot",          "cdata.jdbc.hubspot.HubSpotDriver"),
            Map.entry("jira",             "cdata.jdbc.jira.JiraDriver"),
            Map.entry("marketo",          "cdata.jdbc.marketo.MarketoDriver"),
            Map.entry("mongodb",          "cdata.jdbc.mongodb.MongoDBDriver"),
            Map.entry("mysql",            "cdata.jdbc.mysql.MySQLDriver"),
            Map.entry("netsuite",         "cdata.jdbc.netsuite.NetSuiteDriver"),
            Map.entry("odatadriver",      "cdata.jdbc.odatadriver.ODataDriver"),
            Map.entry("oracle",           "cdata.jdbc.oracle.OracleDriver"),
            Map.entry("oraclesalescloud", "cdata.jdbc.oraclesalescloud.OracleSalesCloudDriver"),
            Map.entry("outreach",         "cdata.jdbc.outreach.OutreachDriver"),
            Map.entry("paypal",           "cdata.jdbc.paypal.PayPalDriver"),
            Map.entry("postgresql",       "cdata.jdbc.postgresql.PostgreSQLDriver"),
            Map.entry("rest",             "cdata.jdbc.rest.RESTDriver"),
            Map.entry("saperp",           "cdata.jdbc.saperp.SAPERPDriver"),
            Map.entry("salesforce",       "cdata.jdbc.salesforce.SalesforceDriver"),
            Map.entry("servicenow",       "cdata.jdbc.servicenow.ServiceNowDriver"),
            Map.entry("sfmarketingcloud", "cdata.jdbc.sfmarketingcloud.SFMarketingCloudDriver"),
            Map.entry("sharepoint",       "cdata.jdbc.sharepoint.SharePointDriver"),
            Map.entry("slack",            "cdata.jdbc.slack.SlackDriver"),
            Map.entry("snowflake",        "cdata.jdbc.snowflake.SnowflakeDriver"),
            Map.entry("sqlserver",        "cdata.jdbc.sqlserver.SQLServerDriver"),
            Map.entry("stripe",           "cdata.jdbc.stripe.StripeDriver"),
            Map.entry("xero",             "cdata.jdbc.xero.XeroDriver"),
            Map.entry("zendesk",          "cdata.jdbc.zendesk.ZendeskDriver"),
            Map.entry("zohocrm",          "cdata.jdbc.zohocrm.ZohoCRMDriver")
    );

    // One classloader per JAR path, reused across loads. A loaded driver keeps
    // referencing its loader for the life of the JVM, so we must NOT close it;
    // caching avoids leaking a fresh loader on every load_driver call.
    private static final ConcurrentHashMap<String, URLClassLoader> LOADER_CACHE = new ConcurrentHashMap<>();

    private static URLClassLoader loaderFor(String jarPath) {
        return LOADER_CACHE.computeIfAbsent(jarPath, p -> {
            try {
                URL jarUrl = new java.io.File(p).toURI().toURL();
                return new URLClassLoader(new URL[]{jarUrl}, Thread.currentThread().getContextClassLoader());
            } catch (Exception e) {
                String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                throw new RuntimeException("Cannot open JAR: " + p + " — " + detail, e);
            }
        });
    }

    /**
     * Resolves a CData driver class name from a short driver name.
     * Returns null if the name cannot be resolved from the known map or the fallback pattern.
     */
    public static String resolveClassName(String driverName) {
        if (driverName == null || driverName.isBlank()) return null;
        String key = driverName.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (KNOWN_DRIVERS.containsKey(key)) return KNOWN_DRIVERS.get(key);
        // CData fallback pattern: cdata.jdbc.<name>.<Capitalized>Driver
        String cap = key.substring(0, 1).toUpperCase() + key.substring(1);
        return "cdata.jdbc." + key + "." + cap + "Driver";
    }

    /**
     * Load a driver from a JAR. Resolution order: the class the JAR declares in its own
     * META-INF/services/java.sql.Driver, then the known CData class registry. Returns the
     * fully-qualified class name actually registered.
     *
     * ServiceLoader is deliberately NOT used for discovery. It walks the classloader delegation
     * chain, so it returns a driver sitting on the server's launch classpath (e.g. the ojdbc jar)
     * and reports it as loaded from the requested JAR — leaving an engineer testing a completely
     * different driver than the one they asked for.
     */
    public static String load(String jarPath) throws Exception {
        requireReadableJar(jarPath);
        List<String> declared = declaredDrivers(jarPath);

        // 1. What the JAR declares about itself is definitive.
        for (String className : declared) {
            try {
                return loadByClass(jarPath, className);
            } catch (ClassNotFoundException ignored) {
                // Declared but absent — malformed JAR; fall through to the registry.
            }
        }

        // 2. Try the known CData classes until one loads *from this JAR*.
        for (String className : KNOWN_DRIVERS.values()) {
            try {
                return loadByClass(jarPath, className);
            } catch (ClassNotFoundException | ForeignDriverException ignored) {
                // Not in this JAR, or it resolved to a copy elsewhere on the classpath.
            }
        }

        throw new Exception("No java.sql.Driver found in: " + jarPath
                + " (checked META-INF/services/java.sql.Driver and the known CData class names). "
                + "Provide driver_name or driver_class explicitly.");
    }

    /**
     * Load a driver by short name (e.g. "sharepoint") — resolves to the full class automatically.
     *
     * When the resolved name is not in the JAR, the JAR's own declaration is consulted before
     * giving up: the fallback pattern only capitalizes the first letter, so "csv" yields
     * CsvDriver where the real class is CSVDriver. A declared class is accepted only when its
     * package matches the requested driver, so pointing "salesforce" at a SharePoint JAR stays
     * an error rather than a silent substitution.
     */
    public static String loadByName(String jarPath, String driverName) throws Exception {
        String className = resolveClassName(driverName);
        if (className == null) throw new Exception("Cannot resolve driver class for: " + driverName);
        try {
            return loadByClass(jarPath, className);
        } catch (ClassNotFoundException notInJar) {
            String key = driverName.toLowerCase().replaceAll("[^a-z0-9]", "");
            List<String> declared = declaredDrivers(jarPath);
            for (String candidate : declared) {
                if (candidate.startsWith("cdata.jdbc." + key + ".")) {
                    return loadByClass(jarPath, candidate);
                }
            }
            throw new Exception("Driver '" + driverName + "' (expected class " + className + ") is not in "
                    + jarPath + ". That JAR declares: " + declared
                    + ". Pass driver_class explicitly, or point jar_path at the right JAR.");
        }
    }

    /**
     * Load a driver by its fully-qualified class name, which must live in the given JAR.
     * Returns that class name.
     */
    public static String loadByClass(String jarPath, String className) throws Exception {
        requireReadableJar(jarPath);
        URLClassLoader loader = loaderFor(jarPath);
        Class<?> clazz = loader.loadClass(className);
        // The loader delegates to the server's classpath, so loadClass succeeding does not mean the
        // class came from this JAR. Refuse anything that didn't — loading a driver is only
        // meaningful if it is the driver under test.
        requireFromJar(clazz, jarPath);
        Driver driver = (Driver) clazz.getDeclaredConstructor().newInstance();
        DriverManager.registerDriver(new DriverShim(driver));
        return className;
    }

    /**
     * Fails fast on a path that isn't a readable JAR. Without this a typo'd path reaches the
     * resolution loops and surfaces as "no driver found", which sends the engineer looking for a
     * driver problem instead of at the path they mistyped.
     */
    private static void requireReadableJar(String jarPath) throws Exception {
        java.io.File f = new java.io.File(jarPath);
        if (!f.exists())      throw new Exception("JAR not found: " + jarPath);
        if (!f.isFile())      throw new Exception("Not a file (directory?): " + jarPath);
        try (JarFile ignored = new JarFile(f)) {
            // Opening succeeds only for a readable, well-formed archive.
        } catch (Exception e) {
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new Exception("Not a readable JAR: " + jarPath + " — " + detail);
        }
    }

    /** Driver class names the JAR declares in META-INF/services/java.sql.Driver, in file order. */
    private static List<String> declaredDrivers(String jarPath) {
        List<String> out = new ArrayList<>();
        try (JarFile jar = new JarFile(jarPath)) {
            JarEntry entry = jar.getJarEntry("META-INF/services/java.sql.Driver");
            if (entry == null) return out;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int hash = line.indexOf('#');
                    if (hash >= 0) line = line.substring(0, hash);
                    line = line.trim();
                    if (!line.isEmpty()) out.add(line);
                }
            }
        } catch (Exception ignored) {
            // Unreadable or not a JAR — callers fall back to the class registry.
        }
        return out;
    }

    /** Throws unless {@code clazz} was actually defined by the given JAR. */
    private static void requireFromJar(Class<?> clazz, String jarPath) throws ForeignDriverException {
        String origin = codeSourceOf(clazz);
        if (origin == null) {
            // No code source (a JVM built-in, or a loader that hides it) — not from the JAR.
            throw new ForeignDriverException(clazz.getName(), "the JVM/bootstrap classpath", jarPath);
        }
        if (!sameFile(origin, jarPath)) {
            throw new ForeignDriverException(clazz.getName(), origin, jarPath);
        }
    }

    private static String codeSourceOf(Class<?> clazz) {
        try {
            java.security.CodeSource cs = clazz.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) return null;
            return Path.of(cs.getLocation().toURI()).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean sameFile(String a, String b) {
        try {
            return Path.of(a).toRealPath().equals(Path.of(b).toRealPath());
        } catch (Exception e) {
            // Unresolvable path (deleted, permissions) — fall back to normalized comparison.
            return Path.of(a).normalize().toAbsolutePath()
                    .equals(Path.of(b).normalize().toAbsolutePath());
        }
    }

    /**
     * A class that resolved to a driver outside the requested JAR — almost always one already on
     * the server's launch classpath. Reports both locations so the cause is obvious.
     */
    public static class ForeignDriverException extends Exception {
        public ForeignDriverException(String className, String actualOrigin, String jarPath) {
            super(className + " was loaded from " + actualOrigin + ", not from the requested JAR ("
                    + jarPath + "). That class is already on the server classpath, so loading it here "
                    + "would register a driver other than the one under test.");
        }
    }

    // DriverManager refuses drivers loaded by a non-system classloader unless shimmed.
    private static class DriverShim implements Driver {
        private final Driver wrapped;

        DriverShim(Driver d) { this.wrapped = d; }

        @Override public java.sql.Connection connect(String url, java.util.Properties info) throws SQLException { return wrapped.connect(url, info); }
        @Override public boolean acceptsURL(String url) throws SQLException { return wrapped.acceptsURL(url); }
        @Override public java.sql.DriverPropertyInfo[] getPropertyInfo(String url, java.util.Properties info) throws SQLException { return wrapped.getPropertyInfo(url, info); }
        @Override public int getMajorVersion() { return wrapped.getMajorVersion(); }
        @Override public int getMinorVersion() { return wrapped.getMinorVersion(); }
        @Override public boolean jdbcCompliant() { return wrapped.jdbcCompliant(); }
        @Override public java.util.logging.Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException { return wrapped.getParentLogger(); }
    }
}
