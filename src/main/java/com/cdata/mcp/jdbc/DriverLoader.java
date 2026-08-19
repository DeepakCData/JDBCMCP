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
import java.util.LinkedHashSet;
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
            Map.entry("oracleoci",        "cdata.jdbc.oracleoci.OracleOCIDriver"),
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

    /** What a load actually registered, and where it came from. */
    public record Loaded(String driverClass, String driverJar, List<String> jars) {}

    // One classloader per JAR *set*, reused across loads. A loaded driver keeps referencing its
    // loader for the life of the JVM, so we must NOT close it; caching avoids leaking a fresh
    // loader on every load_driver call. The key covers every jar, so adding a companion jar
    // produces a new loader rather than silently reusing one that lacks it.
    private static final ConcurrentHashMap<String, URLClassLoader> LOADER_CACHE = new ConcurrentHashMap<>();

    private static URLClassLoader loaderFor(List<String> jars) {
        String key = String.join(java.io.File.pathSeparator, jars);
        return LOADER_CACHE.computeIfAbsent(key, k -> {
            try {
                URL[] urls = new URL[jars.size()];
                for (int i = 0; i < jars.size(); i++) {
                    urls[i] = new java.io.File(jars.get(i)).toURI().toURL();
                }
                return new URLClassLoader(urls, Thread.currentThread().getContextClassLoader());
            } catch (Exception e) {
                String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                throw new RuntimeException("Cannot open JAR set: " + k + " — " + detail, e);
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
     * Load a driver from a JAR set. Resolution order: the classes the JARs declare in their own
     * META-INF/services/java.sql.Driver, then the known CData class registry.
     *
     * ServiceLoader is deliberately NOT used for discovery. It walks the classloader delegation
     * chain, so it returns a driver sitting on the server's launch classpath (e.g. an ojdbc jar)
     * and reports it as loaded from the requested JAR — leaving an engineer testing a completely
     * different driver than the one they asked for.
     */
    public static Loaded load(List<String> jars) throws Exception {
        List<String> resolved = normalize(jars);
        for (String jar : resolved) requireReadableJar(jar);

        // 1. What the JARs declare about themselves is definitive.
        for (String className : declaredDrivers(resolved)) {
            try {
                return loadResolved(resolved, className);
            } catch (ClassNotFoundException ignored) {
                // Declared but absent — malformed JAR; keep looking.
            }
        }

        // 2. Try the known CData classes until one loads *from this JAR set*.
        for (String className : KNOWN_DRIVERS.values()) {
            try {
                return loadResolved(resolved, className);
            } catch (ClassNotFoundException | ForeignDriverException ignored) {
                // Not in these JARs, or it resolved to a copy elsewhere on the classpath.
            }
        }

        throw new Exception("No java.sql.Driver found in: " + resolved
                + " (checked META-INF/services/java.sql.Driver and the known CData class names). "
                + "Provide driver_name or driver_class explicitly.");
    }

    /**
     * Load a driver by short name (e.g. "sharepoint") — resolves to the full class automatically.
     *
     * When the resolved name is not in the JAR set, their own declarations are consulted before
     * giving up: the fallback pattern only capitalizes the first letter, so "csv" yields CsvDriver
     * where the real class is CSVDriver. A declared class is accepted only when its package matches
     * the requested driver, so pointing "salesforce" at a SharePoint JAR stays an error rather than
     * a silent substitution.
     */
    public static Loaded loadByName(List<String> jars, String driverName) throws Exception {
        String className = resolveClassName(driverName);
        if (className == null) throw new Exception("Cannot resolve driver class for: " + driverName);
        List<String> resolved = normalize(jars);
        for (String jar : resolved) requireReadableJar(jar);
        try {
            return loadResolved(resolved, className);
        } catch (ClassNotFoundException notInJar) {
            String key = driverName.toLowerCase().replaceAll("[^a-z0-9]", "");
            List<String> declared = declaredDrivers(resolved);
            for (String candidate : declared) {
                if (candidate.startsWith("cdata.jdbc." + key + ".")) {
                    return loadResolved(resolved, candidate);
                }
            }
            throw new Exception("Driver '" + driverName + "' (expected class " + className + ") is not in "
                    + resolved + ". Those JARs declare: " + declared
                    + ". Pass driver_class explicitly, or point jar_path at the right JAR.");
        }
    }

    /**
     * Load a driver by its fully-qualified class name, which must live in one of the given JARs.
     */
    public static Loaded loadByClass(List<String> jars, String className) throws Exception {
        List<String> resolved = normalize(jars);
        for (String jar : resolved) requireReadableJar(jar);
        return loadResolved(resolved, className);
    }

    /** Loads and registers {@code className}, verifying it came from the supplied JARs. */
    private static Loaded loadResolved(List<String> jars, String className) throws Exception {
        URLClassLoader loader = loaderFor(jars);
        Class<?> clazz = loader.loadClass(className);
        // The loader delegates to the server's classpath, so loadClass succeeding does not mean the
        // class came from these JARs. Refuse anything that didn't — loading a driver is only
        // meaningful if it is the driver under test.
        String origin = requireFromAny(clazz, jars);
        Driver driver = (Driver) clazz.getDeclaredConstructor().newInstance();
        DriverManager.registerDriver(new DriverShim(driver));
        return new Loaded(className, origin, jars);
    }

    // ---- single-JAR convenience overloads -------------------------------------------------

    public static Loaded load(String jarPath) throws Exception {
        return load(List.of(jarPath));
    }

    public static Loaded loadByName(String jarPath, String driverName) throws Exception {
        return loadByName(List.of(jarPath), driverName);
    }

    public static Loaded loadByClass(String jarPath, String className) throws Exception {
        return loadByClass(List.of(jarPath), className);
    }

    // --------------------------------------------------------------------------------------

    /**
     * A hint for the "driver needs a companion JAR" case, or null when the failure is something else.
     *
     * <p>Several CData drivers are thin wrappers that require the vendor's own JDBC/native JAR
     * alongside them — Oracle OCI needs the Oracle client classes, for instance. The symptom is not
     * a connection error but a missing class surfacing at connect time, which reads as an unrelated
     * crash unless you know to look for it. Scans the whole cause chain, since drivers wrap it.
     */
    public static String companionJarHint(Throwable t) {
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable c = t; c != null && seen.add(c); c = c.getCause()) {
            String missing = missingClassOf(c);
            if (missing != null) {
                return "The driver could not find " + missing + ". This usually means the connector"
                        + " needs a companion JAR that was not loaded — several CData drivers (Oracle OCI"
                        + " and other native/thin wrappers) require the vendor's own JDBC or client JAR"
                        + " alongside the CData one. Re-run load_driver with the CData JAR as jar_path"
                        + " and the vendor JAR in extra_jars, then connect again.";
            }
        }
        return null;
    }

    /** The missing class named by a linkage failure, or null when {@code t} is not one. */
    private static String missingClassOf(Throwable t) {
        if (t instanceof ClassNotFoundException || t instanceof NoClassDefFoundError) {
            String msg = t.getMessage();
            if (msg == null || msg.isBlank()) return t.getClass().getSimpleName();
            // NoClassDefFoundError uses slashes: com/foo/Bar
            return msg.trim().replace('/', '.');
        }
        if (t instanceof UnsatisfiedLinkError) {
            return "a native library (" + (t.getMessage() != null ? t.getMessage().trim() : "unnamed") + ")";
        }
        return null;
    }

    /** Distinct, non-blank jar paths in the order given. */
    private static List<String> normalize(List<String> jars) throws Exception {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (jars != null) {
            for (String j : jars) if (j != null && !j.isBlank()) out.add(j.trim());
        }
        if (out.isEmpty()) throw new Exception("At least one jar_path is required");
        return new ArrayList<>(out);
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

    /** Driver class names the JARs declare in META-INF/services/java.sql.Driver, in jar order. */
    private static List<String> declaredDrivers(List<String> jars) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String jarPath : jars) {
            try (JarFile jar = new JarFile(jarPath)) {
                JarEntry entry = jar.getJarEntry("META-INF/services/java.sql.Driver");
                if (entry == null) continue;
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
        }
        return new ArrayList<>(out);
    }

    /**
     * Returns the supplied JAR that defined {@code clazz}, or throws when it came from elsewhere.
     * Any of the requested JARs is acceptable — the point is to exclude the server's own classpath,
     * not to insist the driver live in the first one.
     */
    private static String requireFromAny(Class<?> clazz, List<String> jars) throws ForeignDriverException {
        String origin = codeSourceOf(clazz);
        if (origin == null) {
            // No code source (a JVM built-in, or a loader that hides it) — not from these JARs.
            throw new ForeignDriverException(clazz.getName(), "the JVM/bootstrap classpath", jars);
        }
        for (String jar : jars) {
            if (sameFile(origin, jar)) return jar;
        }
        throw new ForeignDriverException(clazz.getName(), origin, jars);
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
     * A class that resolved to a driver outside the requested JARs — almost always one already on
     * the server's launch classpath. Reports both locations so the cause is obvious.
     */
    public static class ForeignDriverException extends Exception {
        public ForeignDriverException(String className, String actualOrigin, List<String> jars) {
            super(className + " was loaded from " + actualOrigin + ", not from the requested JAR(s) ("
                    + jars + "). That class is already on the server classpath, so loading it here "
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
