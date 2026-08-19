package com.cdata.mcp.jdbc.proxy;

import com.cdata.mcp.jdbc.ConnectionSession;
import com.cdata.mcp.jdbc.ReadOnlyGuard;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ProxyPreparedStatement implements InvocationHandler {

    private final PreparedStatement real;
    private final ConnectionSession session;
    private final String sql;
    private final Map<Integer, Object> params = new LinkedHashMap<>();

    // One snapshot of the bound parameters per addBatch(), so the trace shows what was batched
    // rather than only the final binding.
    private final List<Map<Integer, Object>> batchParams = new ArrayList<>();

    public ProxyPreparedStatement(PreparedStatement real, ConnectionSession session, String sql) {
        this.real = real;
        this.session = session;
        this.sql = sql;
    }

    public static PreparedStatement wrap(PreparedStatement real, ConnectionSession session, String sql) {
        return (PreparedStatement) Proxy.newProxyInstance(
                real.getClass().getClassLoader(),
                new Class[]{PreparedStatement.class},
                new ProxyPreparedStatement(real, session, sql));
    }

    /**
     * Wrap a CallableStatement from {@code prepareCall}. These used to escape the proxy entirely:
     * no intercepted calls, and — since the ResultSet was never wrapped either — no query deadline
     * across row fetching. The same handler serves both, since dispatch is by method name.
     */
    public static CallableStatement wrapCall(CallableStatement real, ConnectionSession session, String sql) {
        return (CallableStatement) Proxy.newProxyInstance(
                real.getClass().getClassLoader(),
                new Class[]{CallableStatement.class},
                new ProxyPreparedStatement(real, session, sql));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();

        // Capture bound parameters: setXxx(int paramIndex, value).
        if (name.startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer idx) {
            // setNull(index, sqlType) carries a type code, not a value — recording args[1] showed a
            // null parameter as the integer 12 (Types.VARCHAR) in the trace.
            params.put(idx, name.equals("setNull") ? null : args[1]);
        }

        if (name.equals("addBatch")) {
            ReadOnlyGuard.check(session, sql, "addBatch");
            batchParams.add(new LinkedHashMap<>(params));
            return ProxyInvoke.call(method, real, args);
        }

        if (name.equals("executeBatch") || name.equals("executeLargeBatch")) {
            return runBatch(method, args, name);
        }

        if (name.equals("executeQuery") || name.equals("executeUpdate") || name.equals("execute")
                || name.equals("executeLargeUpdate")) {
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
                session.addCall(new InterceptedCall(name, sql, new LinkedHashMap<>(params), dur, rows, error));
            }
            return wrapResultSet(result);
        }

        // getResultSet() feeds the EXEC path, which reaches rows without executeQuery().
        return wrapResultSet(ProxyInvoke.call(method, real, args));
    }

    /** Execute a queued batch, recording the batch size and the parameter sets that were sent. */
    private Object runBatch(Method method, Object[] args, String name) throws Throwable {
        int size = batchParams.size();
        // Flatten the first parameter set for the trace's params field; the count carries the rest.
        Map<Integer, Object> sample = batchParams.isEmpty() ? null : new LinkedHashMap<>(batchParams.get(0));
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
            session.addCall(InterceptedCall.batch(name, sql, sample, dur, size,
                    ProxyStatement.totalRows(result), error));
            batchParams.clear();
        }
        return result;
    }

    /** Route every ResultSet leaving this statement through the budget-enforcing proxy. */
    private Object wrapResultSet(Object result) {
        return (result instanceof ResultSet rs) ? ProxyResultSet.wrap(rs, session) : result;
    }
}
