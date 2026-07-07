package com.cdata.mcp.jdbc;

import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

public class DriverLoader {

    /** Known CData JDBC driver class names keyed by short driver name (lowercase). */
    public static final Map<String, String> KNOWN_DRIVERS = Map.ofEntries(
            Map.entry("acumatica",        "cdata.jdbc.acumatica.AcumaticaDriver"),
            Map.entry("bigquery",         "cdata.jdbc.googlebigquery.GoogleBigQueryDriver"),
            Map.entry("box",              "cdata.jdbc.box.BoxDriver"),
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
                throw new RuntimeException("Cannot open JAR: " + p + " — " + e.getMessage(), e);
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
     * Load a driver from a JAR. Tries ServiceLoader first, then the known CData class registry,
     * then fails with guidance. Returns the fully-qualified class name actually registered.
     */
    public static String load(String jarPath) throws Exception {
        URLClassLoader loader = loaderFor(jarPath);

        // 1. ServiceLoader (works for drivers that declare META-INF/services/java.sql.Driver)
        ServiceLoader<Driver> sl = ServiceLoader.load(Driver.class, loader);
        for (Driver driver : sl) {
            DriverManager.registerDriver(new DriverShim(driver));
            return driver.getClass().getName();
        }

        // 2. Try every known CData driver class until one loads
        for (String className : KNOWN_DRIVERS.values()) {
            try {
                Class<?> clazz = loader.loadClass(className);
                Driver driver = (Driver) clazz.getDeclaredConstructor().newInstance();
                DriverManager.registerDriver(new DriverShim(driver));
                return className;
            } catch (ClassNotFoundException ignored) {
                // not this one
            }
        }

        throw new Exception("No java.sql.Driver found via ServiceLoader or known class names in: "
                + jarPath + ". Provide driver_name or driver_class explicitly.");
    }

    /**
     * Load a driver by short name (e.g. "sharepoint") — resolves to the full class automatically.
     */
    public static String loadByName(String jarPath, String driverName) throws Exception {
        String className = resolveClassName(driverName);
        if (className == null) throw new Exception("Cannot resolve driver class for: " + driverName);
        return loadByClass(jarPath, className);
    }

    /**
     * Load a driver by its fully-qualified class name. Returns that class name.
     */
    public static String loadByClass(String jarPath, String className) throws Exception {
        URLClassLoader loader = loaderFor(jarPath);
        Class<?> clazz = loader.loadClass(className);
        Driver driver = (Driver) clazz.getDeclaredConstructor().newInstance();
        DriverManager.registerDriver(new DriverShim(driver));
        return className;
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
