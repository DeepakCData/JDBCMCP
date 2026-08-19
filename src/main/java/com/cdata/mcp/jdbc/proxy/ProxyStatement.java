package com.cdata.mcp.jdbc.proxy;

import com.cdata.mcp.jdbc.ConnectionSession;
import com.cdata.mcp.jdbc.ReadOnlyGuard;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class ProxyStatement implements InvocationHandler {

    private final Statement real;
    private final ConnectionSession session;
    private final String preparedSql;

    // SQL queued via addBatch(sql). Kept so executeBatch can report what it ran: batch calls used
    // to produce no intercepted_calls at all, leaving the batch checklist with an empty trace.
    private final List<String> batch = new ArrayList<>();

    public ProxyStatement(Statement real, ConnectionSession session, String preparedSql) {
        this.real = real;
        this.session = session;
        this.preparedSql = preparedSql;
    }

    public static Statement wrap(Statement real, ConnectionSession session, String preparedSql) {
        return (Statement) Proxy.newProxyInstance(
                real.getClass().getClassLoader(),
                new Class[]{Statement.class},
                new ProxyStatement(real, session, preparedSql));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();

        if (name.equals("addBatch")) {
            String sql = (args != null && args.length > 0) ? (String) args[0] : null;
            ReadOnlyGuard.check(session, sql, "addBatch");
            if (sql != null) batch.add(sql);
            return ProxyInvoke.call(method, real, args);
        }

        if (name.equals("executeBatch") || name.equals("executeLargeBatch")) {
            return runBatch(method, args, name);
        }

        if (name.equals("executeQuery") || name.equals("executeUpdate") || name.equals("execute")
                || name.equals("executeLargeUpdate")) {
            String sql = (preparedSql != null) ? preparedSql
                    : (args != null && args.length > 0 ? (String) args[0] : null);
            ReadOnlyGuard.check(session, sql, name);
            long start = System.currentTimeMillis();
            String error = null;
            Object result = null;
            try {
                result = ProxyInvoke.call(method, real, args);
            } catch (Throwable t) {
                error = ProxyInvoke.describe(t);
                throw t;
            } finally {
                long dur = System.currentTimeMillis() - start;
                int rows = -1;
                if (result instanceof Integer i) {
                    rows = i;
                } else if (result instanceof Long l) {
                    rows = l > Integer.MAX_VALUE ? Integer.MAX_VALUE : l.intValue();
                }
                // For a ResultSet the row count is unknown until iteration; the tool layer patches it.
                session.addCall(new InterceptedCall(name, sql, null, dur, rows, error));
            }
            return wrapResultSet(result);
        }

        // getResultSet() feeds the EXEC path, which reaches rows without executeQuery().
        return wrapResultSet(ProxyInvoke.call(method, real, args));
    }

    /** Execute a queued batch, recording one call that reports the batch size and total rows. */
    private Object runBatch(Method method, Object[] args, String name) throws Throwable {
        int size = batch.size();
        String sql = batch.isEmpty() ? preparedSql : String.join("; ", batch);
        long start = System.currentTimeMillis();
        String error = null;
        Object result = null;
        try {
            result = ProxyInvoke.call(method, real, args);
        } catch (Throwable t) {
            error = ProxyInvoke.describe(t);
            throw t;
        } finally {
            long dur = System.currentTimeMillis() - start;
            session.addCall(InterceptedCall.batch(name, sql, null, dur, size, totalRows(result), error));
            batch.clear();
        }
        return result;
    }

    /** Sum of the per-statement update counts a batch returned, or -1 when unavailable. */
    static int totalRows(Object result) {
        int total = 0;
        if (result instanceof int[] counts) {
            for (int c : counts) if (c > 0) total += c;
            return total;
        }
        if (result instanceof long[] counts) {
            for (long c : counts) if (c > 0) total += (int) c;
            return total;
        }
        return -1;
    }

    /** Route every ResultSet leaving this statement through the budget-enforcing proxy. */
    private Object wrapResultSet(Object result) {
        return (result instanceof ResultSet rs) ? ProxyResultSet.wrap(rs, session) : result;
    }
}
